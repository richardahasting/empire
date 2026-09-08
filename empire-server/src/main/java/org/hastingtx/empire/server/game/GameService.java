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
        final ReentrantLock lock = new ReentrantLock();
        Game(GameRow row, GameConfig cfg, World world) {
            this.id = row.id(); this.cfg = cfg; this.com = Commodities.of(cfg); this.exec = new CommandExecutor(cfg);
            this.name = row.name(); this.preset = row.preset(); this.seed = row.seed(); this.world = world; this.status = row.status();
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
        Game g = new Game(games.find(id).orElseThrow(), cfg, world);
        g.status = "running";
        loaded.put(id, g);
        log.info("created game {} '{}' preset {} seed {} countries {}", id, name, preset, seed, countryNames);
        return g;
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
            logs.update(gameId, n, seed, r.stateHash(), r.events(), r.flows(), ms);
            g.world = r.next();
            log.info("game {} update {} in {} ms, hash {}", gameId, n, ms, r.stateHash().substring(0, 12));
            return r;
        } finally { g.lock.unlock(); }
    }

    // ------------------------------------------------------------------------------ players
    public record CountrySeat(int id, String name, boolean taken) {}
    public record Summary(long id, String name, String preset, String status, long updateNumber, int width, int height, List<CountrySeat> countries, Integer myCountry) {}

    public Summary summary(Game g, Account a) {
        List<CountrySeat> seats = new ArrayList<>();
        Integer mine = null;
        for (GameRepository.Seat s : games.seats(g.id)) {
            seats.add(new CountrySeat(s.countryId(), s.name(), s.accountId() != null));
            if (a != null && s.accountId() != null && s.accountId() == a.id()) mine = s.countryId();
        }
        return new Summary(g.id, g.name, g.preset, g.status, g.world.updateNumber(), g.world.width(), g.world.height(), seats, mine);
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

    public record Outcome(boolean accepted, String error, double btuSpent, CountryView view) {}

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
            return new Outcome(r.ok(), relativise(g.world, g.world.country(country).capital(), r.error()), r.btuSpent(), CountryView.of(g.world, g.cfg, country));
        } finally { g.lock.unlock(); }
    }

    public Country country(long gameId, int id) { return get(gameId).world.country(id); }
    public LogRepository.UpdateEntry lastUpdate(long gameId) { return logs.lastUpdate(gameId); }
}
