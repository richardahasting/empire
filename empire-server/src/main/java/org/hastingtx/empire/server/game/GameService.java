package org.hastingtx.empire.server.game;

import jakarta.annotation.PostConstruct;
import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.persistence.GameRepository;
import org.hastingtx.empire.server.persistence.GameRow;
import org.hastingtx.empire.server.persistence.LogRepository;
import org.hastingtx.empire.server.persistence.WorldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Every loaded game lives in memory as an immutable World behind a lock; the database is
 * written through after each command and each update and read only at startup.
 */
@Service
public class GameService {
    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    /** Mutable holder for one game. */
    public static final class Game {
        public final long id; public final GameConfig cfg; public final Commodities com; public final CommandExecutor exec;
        public final String name, preset; public final long seed;
        public volatile World world; public volatile String status;
        public volatile long intervalSeconds; public volatile java.time.Instant nextUpdateAt;
        final ReentrantLock lock = new ReentrantLock();
        Game(GameRow row, GameConfig cfg, World world) {
            this.id = row.id(); this.cfg = cfg; this.com = Commodities.of(cfg); this.exec = new CommandExecutor(cfg);
            this.name = row.name(); this.preset = row.preset(); this.seed = row.seed(); this.world = world; this.status = row.status();
            this.intervalSeconds = row.intervalSeconds(); this.nextUpdateAt = row.nextUpdateAt();
        }
    }

    private final GameRepository games;
    private final WorldRepository worlds;
    private final LogRepository logs;
    private final ConfigLoader loader = new ConfigLoader();
    private final Map<Long, Game> loaded = new ConcurrentHashMap<>();

    public GameService(GameRepository games, WorldRepository worlds, LogRepository logs) { this.games = games; this.worlds = worlds; this.logs = logs; }

    @PostConstruct
    void loadAll() {
        for (GameRow row : games.all()) {
            try {
                GameConfig cfg = loader.loadYaml(row.configYaml()).config();
                loaded.put(row.id(), new Game(row, cfg, worlds.load(row, cfg)));
                log.info("loaded game {} '{}' ({}x{}, update {})", row.id(), row.name(), row.width(), row.height(), row.updateNumber());
            } catch (RuntimeException e) {
                log.error("cannot load game {} '{}': {}", row.id(), row.name(), e.toString());
            }
        }
    }

    public Game get(long id) {
        Game g = loaded.get(id);
        if (g == null) throw new NoSuchElementException("no game " + id);
        return g;
    }

    public Collection<Game> all() { return loaded.values().stream().sorted(Comparator.comparingLong(g -> g.id)).toList(); }

    // ------------------------------------------------------------------------------ admin
    public Game create(String preset, String name, List<String> countryNames, long seed, Long createdBy) {
        if (countryNames == null || countryNames.isEmpty()) throw new IllegalArgumentException("at least one country");
        if (new HashSet<>(countryNames).size() != countryNames.size()) throw new IllegalArgumentException("country names must be unique");
        ConfigLoader.Loaded l = loader.loadPreset(preset);
        GameConfig cfg = l.config();
        World world = new WorldGenerator(cfg).generate(countryNames, seed);
        long id = games.create(name, preset, loader.toYaml(l.raw()), l.hash(), seed, world.width(), world.height(), world.wrapX(), world.wrapY(), createdBy);
        worlds.saveAll(id, world, Commodities.of(cfg));
        games.setStatus(id, "running");
        long interval = parseInterval(cfg.schedule().updateInterval());
        games.setSchedule(id, interval, interval > 0 ? java.time.Instant.now().plusSeconds(interval) : null);
        Game g = new Game(games.find(id).orElseThrow(), cfg, world);
        g.status = "running";
        loaded.put(id, g);
        log.info("created game {} '{}' preset {} seed {} countries {}", id, name, preset, seed, countryNames);
        return g;
    }

    /**
     * Re-read a game's rules from its preset as shipped now. A game snapshots its config at
     * creation, so a rule change never reaches a running game on its own (issues #36, #40); this
     * replaces the snapshot and reloads the world under the new config. Commodity and sector-type
     * lists must not have changed shape — stocks are stored by commodity id, so adding one is fine.
     */
    public Summary refreshConfig(long gameId, Account a) {
        Game old = get(gameId);
        old.lock.lock();
        try {
            ConfigLoader.Loaded l = loader.loadPreset(old.preset);
            games.setConfig(gameId, loader.toYaml(l.raw()), l.hash());
            GameRow row = games.find(gameId).orElseThrow();
            Game g = new Game(row, l.config(), worlds.load(row, l.config()));
            loaded.put(gameId, g);
            log.info("game {} '{}': rules reloaded from preset {} (config {})", gameId, row.name(), old.preset, l.hash().substring(0, 12));
            return summary(g, a);
        } finally { old.lock.unlock(); }
    }

    /** "24h", "15m", "90s", "1h30m"; "0" or blank = manual. */
    public static long parseInterval(String spec) {
        if (spec == null || spec.isBlank() || spec.trim().equals("0")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([hms])").matcher(spec.trim().toLowerCase());
        long total = 0; boolean any = false;
        while (m.find()) { any = true; long n = Long.parseLong(m.group(1)); total += switch (m.group(2)) { case "h" -> n * 3600; case "m" -> n * 60; default -> n; }; }
        if (!any) throw new IllegalArgumentException("interval looks like 24h, 15m or 0: " + spec);
        return total;
    }

    public void setSchedule(long gameId, long intervalSeconds) {
        Game g = get(gameId);
        if (intervalSeconds < 0) throw new IllegalArgumentException("interval must be >= 0");
        g.intervalSeconds = intervalSeconds;
        g.nextUpdateAt = intervalSeconds > 0 ? java.time.Instant.now().plusSeconds(intervalSeconds) : null;
        games.setSchedule(gameId, g.intervalSeconds, g.nextUpdateAt);
        log.info("game {} schedule: every {}s, next {}", gameId, intervalSeconds, g.nextUpdateAt);
    }

    public void setStatus(long gameId, String status) {
        Game g = get(gameId);
        if (!java.util.Set.of("running", "paused", "finished").contains(status)) throw new IllegalArgumentException("status must be running, paused or finished");
        g.status = status;
        games.setStatus(gameId, status);
        if (status.equals("running") && g.intervalSeconds > 0 && (g.nextUpdateAt == null || g.nextUpdateAt.isBefore(java.time.Instant.now()))) {
            g.nextUpdateAt = java.time.Instant.now().plusSeconds(g.intervalSeconds);
            games.setSchedule(gameId, g.intervalSeconds, g.nextUpdateAt);
        }
    }

    /** Called by the scheduler: run every due update. A failed update pauses that game and is logged; the world is untouched. */
    public void tick() {
        java.time.Instant now = java.time.Instant.now();
        for (Game g : loaded.values()) {
            if (!"running".equals(g.status) || g.intervalSeconds <= 0 || g.nextUpdateAt == null || g.nextUpdateAt.isAfter(now)) continue;
            try {
                forceUpdate(g.id);
                java.time.Instant next = g.nextUpdateAt.plusSeconds(g.intervalSeconds);
                if (next.isBefore(now)) next = now.plusSeconds(g.intervalSeconds);   // missed several: do not stampede
                g.nextUpdateAt = next;
                games.setSchedule(g.id, g.intervalSeconds, next);
            } catch (RuntimeException e) {
                log.error("scheduled update failed for game {} — pausing it: {}", g.id, e.toString());
                g.status = "paused"; games.setStatus(g.id, "paused");
            }
        }
    }

    public org.hastingtx.empire.engine.update.Projection.Result projection(long gameId, Account a) {
        Game g = get(gameId);
        int country = myCountry(gameId, a);
        World w = g.world;
        return org.hastingtx.empire.engine.update.Projection.of(w, g.cfg, country, g.seed * 1_000_003L + w.updateNumber() + 1);
    }

    public UpdateResult forceUpdate(long gameId) {
        Game g = get(gameId);
        g.lock.lock();
        try {
            long n = g.world.updateNumber() + 1;
            long seed = g.seed * 1_000_003L + n;
            long t0 = System.nanoTime();
            UpdateResult r = Update.run(g.world, g.cfg, seed);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            worlds.saveDiff(gameId, g.world, r.next(), g.com);
            logs.update(gameId, n, seed, r.stateHash(), r.events(), r.flows(), ms, r.notes());
            g.world = r.next();
            log.info("game {} update {} in {} ms, hash {}", gameId, n, ms, r.stateHash().substring(0, 12));
            return r;
        } finally { g.lock.unlock(); }
    }

    // ------------------------------------------------------------------------------ players
    public record CountrySeat(int id, String name, boolean taken) {}
    public record Summary(long id, String name, String preset, String status, long updateNumber, int width, int height, List<CountrySeat> countries, Integer myCountry,
                          long intervalSeconds, java.time.Instant nextUpdateAt) {}

    public Summary summary(Game g, Account a) {
        List<CountrySeat> seats = new ArrayList<>();
        Integer mine = null;
        for (GameRepository.Seat s : games.seats(g.id)) {
            seats.add(new CountrySeat(s.countryId(), s.name(), s.accountId() != null));
            if (a != null && s.accountId() != null && s.accountId() == a.id()) mine = s.countryId();
        }
        return new Summary(g.id, g.name, g.preset, g.status, g.world.updateNumber(), g.world.width(), g.world.height(), seats, mine, g.intervalSeconds, g.nextUpdateAt);
    }

    public Summary join(long gameId, Account a, int countryId) {
        Game g = get(gameId);
        if (games.countryOf(gameId, a.id()).isPresent()) throw new IllegalArgumentException("you already have a country in this game");
        if (countryId < 0 || countryId >= g.world.countries().size()) throw new IllegalArgumentException("no such country");
        if (games.bind(gameId, countryId, a.id()) != 1) throw new IllegalArgumentException("that country is taken");
        return summary(g, a);
    }

    public int myCountry(long gameId, Account a) {
        return games.countryOf(gameId, a.id()).orElseThrow(() -> new IllegalArgumentException("you have no country in this game"));
    }

    public CountryView view(long gameId, Account a) {
        Game g = get(gameId);
        return CountryView.of(g.world, g.cfg, myCountry(gameId, a));
    }

    public record Outcome(boolean accepted, String error, double btuSpent, CountryView view, String info) {}

    private static final java.util.regex.Pattern ABS = java.util.regex.Pattern.compile("(?<![\\d.,-])(\\d+),(\\d+)(?![\\d.])");

    /** Engine messages name sectors by absolute coordinates; players only ever see offsets from their capital. */
    public static String relativise(World w, Coord capital, String msg) {
        if (msg == null) return null;
        java.util.regex.Matcher m = ABS.matcher(msg);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Coord abs = new Coord(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            Coord r = w.inBounds(abs) ? CountryView.relative(w, capital, abs) : abs;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(r.x() + "," + r.y()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public Outcome command(long gameId, Account a, Command cmd, String source) {
        Game g = get(gameId);
        int country = myCountry(gameId, a);
        if (!"running".equals(g.status)) throw new IllegalArgumentException("game is " + g.status);
        g.lock.lock();
        try {
            World before = g.world;
            CommandResult r = g.exec.execute(before, country, cmd);
            logs.command(gameId, country, before.updateNumber(), source, cmd.verb(), cmd, r.ok(), r.error(), r.btuSpent());
            if (r.ok()) { worlds.saveDiff(gameId, before, r.world(), g.com); g.world = r.world(); }
            Coord cap = g.world.country(country).capital();
            return new Outcome(r.ok(), relativise(g.world, cap, r.error()), r.btuSpent(), CountryView.of(g.world, g.cfg, country), relativise(g.world, cap, r.info()));
        } finally { g.lock.unlock(); }
    }

    /**
     * Many commands as one action (issue #38): run in order on the evolving world under one lock,
     * saved once, logged per command. Each sector pays its own BTU; when BTUs run out the rest are
     * skipped and the reply says so. Partial success is success — the summary lists what was skipped.
     */
    public Outcome commandAll(long gameId, Account a, List<Command> cmds, String source) { return commandAll(gameId, a, cmds, source, null); }

    /** As above; {@code note} (e.g. "warehouse ×10") is appended to the summary when given. */
    public Outcome commandAll(long gameId, Account a, List<Command> cmds, String source, String note) {
        if (cmds.isEmpty()) throw new IllegalArgumentException("nothing to do");
        if (cmds.size() == 1 && note == null) return command(gameId, a, cmds.get(0), source);
        Game g = get(gameId);
        int country = myCountry(gameId, a);
        if (!"running".equals(g.status)) throw new IllegalArgumentException("game is " + g.status);
        g.lock.lock();
        try {
            World before = g.world, cur = before;
            Coord cap = before.country(country).capital();
            int applied = 0, outOfBtu = 0; double btu = 0;
            List<String> skipped = new ArrayList<>(), notes = new ArrayList<>();
            for (int i = 0; i < cmds.size(); i++) {
                Command cmd = cmds.get(i);
                CommandResult r = g.exec.execute(cur, country, cmd);
                logs.command(gameId, country, before.updateNumber(), source, cmd.verb(), cmd, r.ok(), r.error(), r.btuSpent());
                if (r.ok()) { cur = r.world(); applied++; btu += r.btuSpent(); if (r.info() != null) notes.add(relativise(before, cap, sectorOf(cmd) + ": " + r.info())); continue; }
                if (r.error().startsWith("not enough BTUs")) { outOfBtu = cmds.size() - i; break; }
                skipped.add(relativise(before, cap, sectorOf(cmd) + ": " + r.error()));
            }
            if (applied > 0) { worlds.saveDiff(gameId, before, cur, g.com); g.world = cur; }
            StringBuilder sb = new StringBuilder("applied " + applied + " of " + cmds.size() + (cmds.size() == 1 ? " command" : " commands"));
            if (!skipped.isEmpty()) {
                sb.append("; skipped ").append(skipped.size()).append(" — ").append(String.join("; ", skipped.subList(0, Math.min(4, skipped.size()))));
                if (skipped.size() > 4) sb.append("; …");
            }
            if (!notes.isEmpty()) {
                sb.append("; ").append(notes.size()).append(" adjusted — ").append(String.join("; ", notes.subList(0, Math.min(3, notes.size()))));
                if (notes.size() > 3) sb.append("; …");
            }
            if (outOfBtu > 0) sb.append("; out of BTUs with ").append(outOfBtu).append(" still to do");
            if (note != null && applied > 0) sb.append(" (").append(note).append(")");
            String msg = sb.toString();
            return new Outcome(applied > 0, applied > 0 ? null : msg, btu, CountryView.of(g.world, g.cfg, country), applied > 0 ? msg : null);
        } finally { g.lock.unlock(); }
    }

    private static String sectorOf(Command c) {
        Coord at = switch (c) {
            case Command.Designate d -> d.sector();
            case Command.Threshold t -> t.sector();
            case Command.Distribute d -> d.sector();
            case Command.Deliver d -> d.sector();
            case Command.BuildRoad r -> r.sector();
            case Command.BuildRail r -> r.sector();
            case Command.Move m -> m.from();
            case Command.Explore e -> e.from();
            case Command.RailShip r -> r.from();
            case Command.BreakSanctuary b -> null;
        };
        return at == null ? c.verb() : at.x() + "," + at.y();
    }

    public Country country(long gameId, int id) { return get(gameId).world.country(id); }
    public LogRepository.UpdateEntry lastUpdate(long gameId) { return logs.lastUpdate(gameId); }
}
