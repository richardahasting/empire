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
import org.hastingtx.empire.engine.model.Levels;
import org.hastingtx.empire.engine.model.Resources;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.Stocks;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.auth.AuthService;
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
        /** Set under {@link #lock} when the game is being deleted, so an update that is already
         *  waiting on the lock abandons rather than writing rows back to a game that is gone. */
        volatile boolean deleted;
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

    private final AuthService auth;

    public GameService(GameRepository games, WorldRepository worlds, LogRepository logs, AuthService auth) { this.games = games; this.worlds = worlds; this.logs = logs; this.auth = auth; }

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

    /**
     * Rebind the preset with a different world. The raw YAML map is patched and reloaded rather than
     * the bound config being mutated, so what the game stores is exactly what it plays by.
     */
    private ConfigLoader.Loaded withWorld(ConfigLoader.Loaded l, WorldOverrides o, int countries) {
        if (o == null || o.empty()) return l;
        return loader.loadYaml(loader.toYaml(o.patch(l.raw(), countries)));
    }

    /** The deity's own country, at absolute 0,0 in every game (issue #128). POGO in the original. */
    public static final String DEITY = "POGO";

    /** The presets the deity can build from, in the order the form offers them. */
    public static final List<String> PRESETS = List.of("teaching", "sandbox", "blitz", "classic");

    /** Each preset's world as shipped, so the create form can show the real defaults it is overriding. */
    public List<org.hastingtx.empire.server.api.AdminController.PresetWorld> presetWorlds() {
        List<org.hastingtx.empire.server.api.AdminController.PresetWorld> out = new ArrayList<>();
        for (String p : PRESETS) {
            var w = loader.loadPreset(p).config().world();
            var t = w.terrain();
            out.add(new org.hastingtx.empire.server.api.AdminController.PresetWorld(
                    p, w.name(), w.width(), w.height(), w.wrapX(), w.wrapY(),
                    Math.round((1.0 - t.landFraction()) * 1000.0) / 10.0,
                    t.islandSize(), t.spike(), t.minDistanceBetweenCapitals(),
                    t.landMix(), loader.loadPreset(p).config().players().maxCountries()));
        }
        return out;
    }


    /**
     * Reject a min-capital-distance the generator cannot satisfy, before it spends 20,000 rejection
     * samples discovering that for itself and throws an IllegalStateException at the deity as a 500.
     *
     * TODO(richard): decide and implement the rule. See the note in the PR/issue #105 for the
     * trade-offs — a strict packing bound rejects worlds that would in fact have generated, and a
     * loose one lets slow failures through.
     */
    private static void checkCapitalsFit(int width, int height, int countries, int minDistance) {
        // TODO: implement
    }


    public Game create(String preset, String name, List<String> countryNames, long seed, Long createdBy) {
        return create(preset, name, countryNames, seed, createdBy, WorldOverrides.NONE);
    }

    /**
     * Create a game, optionally overriding the preset's world (issues #81, #105). A null field in
     * {@code overrides} keeps whatever the preset says. The overrides are written into the game's
     * stored config snapshot, so the game keeps playing by them for life.
     */
    public Game create(String preset, String name, List<String> countryNames, long seed, Long createdBy,
                       WorldOverrides overrides) {
        return createSeats(preset, name, countryNames, seed, createdBy, overrides);
    }

    /**
     * Create a game with {@code seats} countries named emp1 … empN (issue #117). They are claimed and
     * renamed by the people who play them (#119), and the game does not start until the bell (#123).
     */
    public Game createWithSeats(String preset, String name, int seats, long seed, Long createdBy, WorldOverrides overrides) {
        WorldOverrides o = overrides == null ? WorldOverrides.NONE : overrides;
        int n = o.countries() != null ? o.countries() : seats;
        if (o.countries() == null)
            o = new WorldOverrides(o.width(), o.height(), o.water(), o.islandSize(), o.spike(),
                    o.minCapitalDistance(), o.wrapX(), o.wrapY(), o.landMix(), n);
        return createSeats(preset, name, WorldOverrides.seatNames(n), seed, createdBy, o);
    }

    private Game createSeats(String preset, String name, List<String> countryNames, long seed, Long createdBy,
                             WorldOverrides overrides) {
        if (countryNames == null || countryNames.isEmpty()) throw new IllegalArgumentException("at least one country");
        if (new HashSet<>(countryNames).size() != countryNames.size()) throw new IllegalArgumentException("country names must be unique");
        ConfigLoader.Loaded l = loader.loadPreset(preset);
        l = withWorld(l, overrides, countryNames.size());
        GameConfig cfg = l.config();
        World world = placing(() -> new WorldGenerator(cfg).generate(countryNames, seed));
        long id = games.create(name, preset, loader.toYaml(l.raw()), l.hash(), seed, world.width(), world.height(), world.wrapX(), world.wrapY(), createdBy);
        // the deity's own country, at the origin (issue #128). Never a seat, never in the roster.
        int deityId = world.countries().size();
        world = new WorldGenerator(cfg).addDeity(world, DEITY, seed);
        worlds.saveAll(id, world, Commodities.of(cfg));
        games.seatDeity(id, deityId);
        // the bell has not rung (issue #123): the game is visible and read-only until its players arrive
        games.setStatus(id, "setup");
        long interval = parseInterval(cfg.schedule().updateInterval());
        games.setSchedule(id, interval, null);
        Game g = new Game(games.find(id).orElseThrow(), cfg, world);
        g.status = "setup";
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

    /** Give an existing game's sea its fishing grounds (issue #56): ocean fertility from the generator, deterministic from the game seed. Land is untouched. */
    public Summary seedSeaFertility(long gameId, Account a) {
        Game g = get(gameId);
        g.lock.lock();
        try {
            World w = g.world;
            int[] sea = new WorldGenerator(g.cfg).seaFertility(w.width(), w.height(), new java.util.SplittableRandom(g.seed ^ 0x5EAF00DL));
            List<Sector> next = new ArrayList<>(w.sectors());
            for (int i = 0; i < next.size(); i++) {
                Sector s = next.get(i);
                if (s.terrain() == org.hastingtx.empire.engine.model.Terrain.OCEAN) next.set(i, s.withTerrain(s.terrain(), s.elevation(), new org.hastingtx.empire.engine.model.Resources(sea[i], 0, 0, 0, 0)));
            }
            World after = w.withSectors(next);
            worlds.saveDiff(gameId, w, after, g.com);
            g.world = after;
            log.info("game {}: fishing grounds seeded", gameId);
            return summary(g, a);
        } finally { g.lock.unlock(); }
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
        if (!java.util.Set.of("setup", "running", "paused", "finished").contains(status)) throw new IllegalArgumentException("status must be setup, running, paused or finished");
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
            if (g.deleted || !"running".equals(g.status) || g.intervalSeconds <= 0 || g.nextUpdateAt == null || g.nextUpdateAt.isAfter(now)) continue;
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

    /**
     * Delete a game and everything it owns (issue #109). Every game-owned table is ON DELETE CASCADE
     * on game(id), so the row delete takes the map, countries, ships, orders and the whole
     * update and command history with it. There is no undo.
     *
     * <p>Ordering matters: the flag goes up and the game leaves the loaded map under its own lock, so
     * an update already in flight finishes first and one already waiting for the lock abandons
     * instead of writing rows back to a game that no longer exists.
     */
    public void delete(long id, Account by) {
        if (!by.admin()) throw new SecurityException("deity only");
        Game g = get(id);
        g.lock.lock();
        try {
            g.deleted = true;
            g.status = "deleted";
            loaded.remove(id);
            games.delete(id);
            log.info("deleted game {} '{}' ({}x{}, {} updates) by account {}",
                    id, g.name, g.world.width(), g.world.height(), g.world.updateNumber(), by.id());
        } finally { g.lock.unlock(); }
    }

    /**
     * The generator places capitals by rejection sampling and, when it cannot, surrenders with an
     * IllegalStateException — which is not mapped and would reach the deity as a 500 with a stack
     * trace after a long wait (issue #108). The packing bound in {@link WorldOverrides} rejects the
     * arrangements that are certainly impossible; this catches the rest, where a legal arrangement
     * exists on paper but the sampler cannot find it, and says so as a 400.
     */
    static World placing(java.util.function.Supplier<World> generate) {
        try {
            return generate.get();
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage()
                    + " — there may be room in principle, but not enough for the generator to find a layout. "
                    + "Move the capitals closer together, take a country out, or make the world bigger.", e);
        }
    }

    // ---------------------------------------------------------------- the deity's own powers (#128)

    /** The whole map, in absolute coordinates, through the deity's own country at the origin. */
    public CountryView deityView(long gameId, Account by) {
        if (!by.admin()) throw new SecurityException("deity only");
        Game g = get(gameId);
        return CountryView.omniscient(g.world, g.cfg, deityCountry(gameId));
    }

    /**
     * The world as one country actually sees it — fog, map memory, contacts and all (issue #128).
     * The deity's own country returns the unfogged view instead, since that is what POGO is for.
     *
     * <p>This shows a deity a player's private view, which is the point: it is the only way to answer
     * "why can this player not see that" without guessing. It is an admin route for that reason.
     */
    public CountryView viewAs(long gameId, int countryId, Account by) {
        if (!by.admin()) throw new SecurityException("deity only");
        Game g = get(gameId);
        if (countryId < 0 || countryId >= g.world.countries().size()) throw new IllegalArgumentException("no country " + countryId);
        return countryId == deityCountryOrMinusOne(gameId)
                ? CountryView.omniscient(g.world, g.cfg, countryId)
                : CountryView.of(g.world, g.cfg, countryId);
    }

    /** Who is in this game, deity included, as the deity's own screen needs to list them. */
    public record Roster(int countryId, String name, String controller, boolean taken, boolean deity) {}

    public List<Roster> roster(long gameId, Account by) {
        if (!by.admin()) throw new SecurityException("deity only");
        get(gameId);
        List<Roster> out = new ArrayList<>();
        for (GameRepository.Seat s : games.seats(gameId))
            out.add(new Roster(s.countryId(), s.name(), s.controller(), s.accountId() != null, "deity".equals(s.controller())));
        return out;
    }

    private int deityCountryOrMinusOne(long gameId) {
        for (GameRepository.Seat s : games.seats(gameId)) if ("deity".equals(s.controller())) return s.countryId();
        return -1;
    }

    /** The id of this game's deity country, or an error saying it predates POGO. */
    public int deityCountry(long gameId) {
        for (GameRepository.Seat s : games.seats(gameId)) if ("deity".equals(s.controller())) return s.countryId();
        throw new IllegalArgumentException("this game has no deity country — it was created before POGO existed");
    }

    /** What a deity may change about a sector. A null field is left alone. */
    public record SectorEdit(Integer owner, String designation, Double efficiency, Double mobility,
                             Map<String, Double> stock, Integer fertility, Integer minerals, Integer gold,
                             Integer oil, Integer uranium, Integer roadLevel, Integer railLevel, Integer radarLevel) {}

    /**
     * Change a sector, by absolute coordinates (issue #128). This is a write outside the update, so it
     * is taken under the game's lock like every other one — and it is logged, because it costs the
     * game two of its guarantees. The update proves that each commodity's total equals the old total
     * plus what was produced and consumed, and that a world replays from (config, seed, commands);
     * a deity conjuring ten thousand iron makes both false. That is a fair price for a tool whose
     * whole purpose is rescuing a broken game, but it should never be a silent one.
     */
    public Sector editSector(long gameId, int x, int y, SectorEdit e, Account by) {
        if (!by.admin()) throw new SecurityException("deity only");
        Game g = get(gameId);
        Coord at = new Coord(x, y);
        if (!g.world.inBounds(at)) throw new IllegalArgumentException(x + "," + y + " is off the map");
        g.lock.lock();
        try {
            World before = g.world;
            Sector s = before.sector(at);
            List<String> changed = new ArrayList<>();

            if (e.owner() != null) {
                if (e.owner() < -1 || e.owner() >= before.countries().size()) throw new IllegalArgumentException("no country " + e.owner());
                changed.add("owner " + s.owner() + "->" + e.owner());
                s = s.withOwner(e.owner());
            }
            if (e.designation() != null) {
                double eff = e.efficiency() != null ? e.efficiency() : s.efficiency();
                changed.add("designation " + s.designation() + "->" + e.designation());
                s = s.withDesignation(e.designation(), eff);
            } else if (e.efficiency() != null) {
                changed.add("efficiency " + Ledger.q(s.efficiency()) + "->" + Ledger.q(e.efficiency()));
                s = s.withDesignation(s.designation(), e.efficiency());
            }
            if (e.mobility() != null) { changed.add("mobility " + Ledger.q(s.mobility()) + "->" + Ledger.q(e.mobility())); s = s.withMobility(e.mobility()); }
            if (e.roadLevel() != null) { changed.add("road " + Ledger.q(s.roadLevel()) + "->" + e.roadLevel()); s = s.withRoadLevel(e.roadLevel()); }
            if (e.railLevel() != null) { changed.add("rail " + Ledger.q(s.railLevel()) + "->" + e.railLevel()); s = s.withRailLevel(e.railLevel()); }
            if (e.radarLevel() != null) { changed.add("radar " + Ledger.q(s.radarLevel()) + "->" + e.radarLevel()); s = s.withRadarLevel(e.radarLevel()); }
            Resources r = s.resources();
            if (e.fertility() != null || e.minerals() != null || e.gold() != null || e.oil() != null || e.uranium() != null) {
                Resources next = new Resources(
                        e.fertility() != null ? e.fertility() : r.fertility(),
                        e.minerals() != null ? e.minerals() : r.minerals(),
                        e.gold() != null ? e.gold() : r.gold(),
                        e.oil() != null ? e.oil() : r.oil(),
                        e.uranium() != null ? e.uranium() : r.uranium());
                changed.add("resources " + next);
                s = s.withTerrain(s.terrain(), s.elevation(), next);
            }
            if (e.stock() != null && !e.stock().isEmpty()) {
                double[] q = s.stock().toArray();
                for (Map.Entry<String, Double> en : e.stock().entrySet()) {
                    int i = g.com.index(en.getKey());
                    if (i < 0) throw new IllegalArgumentException("no commodity " + en.getKey());
                    q[i] = en.getValue();
                }
                changed.add("stock " + e.stock());
                s = s.withStock(Stocks.of(q));
            }
            if (changed.isEmpty()) throw new IllegalArgumentException("nothing to change");

            World after = before.withSector(s);
            worlds.saveDiff(gameId, before, after, g.com);
            g.world = after;
            log.warn("DEITY EDIT game {} sector {},{} by account {}: {}", gameId, x, y, by.id(), String.join("; ", changed));
            logs.deityEdit(gameId, after.updateNumber(), "sector " + x + "," + y, String.join("; ", changed), by.id());
            return s;
        } finally { g.lock.unlock(); }
    }

    /** What a deity may change about a country. A null field is left alone. */
    public record CountryEdit(Double cash, Double btu, Double tech, Double research, Double education,
                              Double happiness, Boolean inSanctuary, Boolean bankrupt) {}

    /** Change a country's national figures (issue #128). Logged, for the same reason as a sector edit. */
    public Country editCountry(long gameId, int countryId, CountryEdit e, Account by) {
        if (!by.admin()) throw new SecurityException("deity only");
        Game g = get(gameId);
        if (countryId < 0 || countryId >= g.world.countries().size()) throw new IllegalArgumentException("no country " + countryId);
        g.lock.lock();
        try {
            World before = g.world;
            Country c = before.country(countryId);
            List<String> changed = new ArrayList<>();
            if (e.cash() != null) { changed.add("cash " + Ledger.q(c.cash()) + "->" + Ledger.q(e.cash())); c = c.withCash(e.cash()); }
            if (e.btu() != null) { changed.add("btu " + Ledger.q(c.btu()) + "->" + Ledger.q(e.btu())); c = c.withBtu(e.btu()); }
            Levels lv = c.levels();
            if (e.tech() != null || e.research() != null || e.education() != null || e.happiness() != null) {
                Levels next = new Levels(
                        e.tech() != null ? e.tech() : lv.tech(),
                        e.research() != null ? e.research() : lv.research(),
                        e.education() != null ? e.education() : lv.education(),
                        e.happiness() != null ? e.happiness() : lv.happiness());
                changed.add("levels " + next);
                c = c.withLevels(next);
            }
            if (e.inSanctuary() != null) { changed.add("sanctuary " + c.inSanctuary() + "->" + e.inSanctuary()); c = c.withSanctuary(e.inSanctuary()); }
            if (e.bankrupt() != null) { changed.add("bankrupt " + c.bankrupt() + "->" + e.bankrupt()); c = c.withBankrupt(e.bankrupt()); }
            if (changed.isEmpty()) throw new IllegalArgumentException("nothing to change");

            List<Country> next = new ArrayList<>(before.countries());
            next.set(countryId, c);
            World after = before.withCountries(next);
            worlds.saveDiff(gameId, before, after, g.com);
            g.world = after;
            log.warn("DEITY EDIT game {} country {} by account {}: {}", gameId, countryId, by.id(), String.join("; ", changed));
            logs.deityEdit(gameId, after.updateNumber(), "country " + countryId + " (" + c.name() + ")", String.join("; ", changed), by.id());
            return c;
        } finally { g.lock.unlock(); }
    }

    /**
     * Mint a fresh token for a bot's seat and revoke whatever it had (issue #128). The token from
     * {@link #addCountry} is shown once and stored only as a hash, so an agent that loses it had no
     * way back in at all — the fix was a second country and a wasted seat.
     */
    public String reissueToken(long gameId, int countryId, Account by) {
        if (!by.admin()) throw new SecurityException("deity only");
        Game g = get(gameId);
        if (countryId < 0 || countryId >= g.world.countries().size()) throw new IllegalArgumentException("no country " + countryId);
        GameRepository.Seat seat = games.seats(gameId).stream().filter(s -> s.countryId() == countryId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no country " + countryId));
        if (!"agent".equals(seat.controller()))
            throw new IllegalArgumentException(seat.name() + " is not a bot seat — a person signs in with a magic link instead");
        AuthService.Session s = auth.reissueAgentSession(seat.name());
        games.seatAgent(gameId, countryId, s.account().id());
        log.warn("DEITY reissued the token for game {} country {} ({}) by account {}", gameId, countryId, seat.name(), by.id());
        return s.token();
    }

    /** Everything a deity has changed by hand in this game, newest first (issue #128). */
    public List<Map<String, Object>> deityEdits(long gameId) {
        get(gameId);
        return logs.deityEdits(gameId);
    }

    /** What came of seating a new country. {@code token} is non-null only for a bot, and only here. */
    public record Seated(int countryId, String name, Coord capital, String controller, String token) {}

    /**
     * Seat a new country in a running game (issue #113). The engine places the capital and sanctuary;
     * this persists the result and opens the seat — left unclaimed for a person to join, or bound
     * immediately to a freshly minted bot account.
     */
    public Seated addCountry(long id, String name, String controller, Account by) {
        if (!by.admin()) throw new SecurityException("deity only");
        boolean agent = "agent".equalsIgnoreCase(controller);
        if (!agent && !"human".equalsIgnoreCase(controller)) throw new IllegalArgumentException("controller is 'human' or 'agent'");
        Game g = get(id);
        g.lock.lock();
        try {
            World before = g.world;
            // Two adjustments to the ceiling. The deity's country is in the world but is not a player,
            // so it does not fill the roster. And the limit here is the hard one, not the game's own
            // max_countries: since #117 a game is created with exactly as many seats as it allows, so
            // checking against that would refuse every Add country there has ever been — and a deity
            // asking for another country is explicit intent overriding a number they set earlier.
            // The real ceiling is still there: the world must physically have somewhere to put it.
            int deities = before.countries().size() - playerSeats(id).size();
            int limit = WorldOverrides.MAX_COUNTRIES + Math.max(0, deities);
            World after = placing(() -> new WorldGenerator(g.cfg).addCountry(before, name, g.seed, limit));
            Country c = after.countries().get(after.countries().size() - 1);
            worlds.saveDiff(id, before, after, g.com);
            g.world = after;

            String token = null;
            if (agent) {
                AuthService.Session s = auth.createAgentSession(c.name());
                games.seatAgent(id, c.id(), s.account().id());
                token = s.token();
            }
            log.info("game {}: seated country {} '{}' at {} as {}", id, c.id(), c.name(), c.capital(), agent ? "agent" : "open seat");
            return new Seated(c.id(), c.name(), c.capital(), agent ? "agent" : "none", token);
        } finally { g.lock.unlock(); }
    }

    public UpdateResult forceUpdate(long gameId) {
        Game g = get(gameId);
        g.lock.lock();
        try {
            if (g.deleted) throw new NoSuchElementException("game " + gameId + " was deleted");
            long n = g.world.updateNumber() + 1;
            long seed = g.seed * 1_000_003L + n;
            long t0 = System.nanoTime();
            UpdateResult r = Update.run(g.world, g.cfg, seed);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            worlds.saveDiff(gameId, g.world, r.next(), g.com);
            // only pay for the hash if this game asked for it (issue #82); it is lazy, so not asking costs nothing
            String stateHash = g.cfg.options().stateHash() ? r.stateHash() : null;
            logs.update(gameId, n, seed, stateHash, r.events(), r.flows(), ms, r.notes());
            g.world = r.next();
            log.info("game {} update {} in {} ms{}", gameId, n, ms, stateHash == null ? "" : ", hash " + stateHash.substring(0, 12));
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
        for (GameRepository.Seat s : playerSeats(g.id)) {
            seats.add(new CountrySeat(s.countryId(), s.name(), s.accountId() != null));
            if (a != null && s.accountId() != null && s.accountId() == a.id()) mine = s.countryId();
        }
        return new Summary(g.id, g.name, g.preset, g.status, g.world.updateNumber(), g.world.width(), g.world.height(), seats, mine, g.intervalSeconds, g.nextUpdateAt);
    }

    /**
     * Claim a seat and name it in one act (issue #119). A seat arrives called emp7 and nobody wants to
     * play as emp7, so the name is required rather than offered — and it is set in the same call as
     * the bind, because a seat that is claimed but still unnamed is the state this exists to prevent.
     *
     * <p>Filling the last seat rings the starting bell (issue #123).
     */
    public Summary join(long gameId, Account a, int countryId, String name) {
        Game g = get(gameId);
        if (games.countryOf(gameId, a.id()).isPresent()) throw new IllegalArgumentException("you already have a country in this game");
        if (countryId < 0 || countryId >= g.world.countries().size()) throw new IllegalArgumentException("no such country");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("your country needs a name");
        g.lock.lock();
        try {
            if (games.bind(gameId, countryId, a.id()) != 1) throw new IllegalArgumentException("that country is taken");
            rename(g, countryId, name);
            if (openSeats(gameId) == 0 && "setup".equals(g.status)) {
                ring(g, "the last seat was taken");
            }
        } finally { g.lock.unlock(); }
        return summary(g, a);
    }

    /** Rename your own country (issue #119). Allowed at any time; names stay unique within a game. */
    public Summary renameMine(long gameId, Account a, String name) {
        Game g = get(gameId);
        int country = myCountry(gameId, a);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("your country needs a name");
        g.lock.lock();
        try { rename(g, country, name); } finally { g.lock.unlock(); }
        return summary(g, a);
    }

    /**
     * Set a country's name in the world and in the database together. Called with the game's lock
     * held. The unique index on (game_id, name) is what enforces distinctness; it raises a
     * DataIntegrityViolationException, which is not mapped to anything, so it is turned into the
     * bad request it actually is.
     */
    private void rename(Game g, int countryId, String name) {
        String trimmed = name.trim();
        if (trimmed.length() > 40) throw new IllegalArgumentException("a country name is at most 40 characters");
        for (Country c : g.world.countries())
            if (c.id() != countryId && c.name().equalsIgnoreCase(trimmed))
                throw new IllegalArgumentException("there is already a country called " + c.name() + " in this game");
        World before = g.world;
        List<Country> next = new ArrayList<>(before.countries());
        next.set(countryId, next.get(countryId).withName(trimmed));
        World after = before.withCountries(next);
        try {
            worlds.saveDiff(g.id, before, after, g.com);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IllegalArgumentException("there is already a country called " + trimmed + " in this game");
        }
        g.world = after;
        log.info("game {}: country {} is now '{}'", g.id, countryId, trimmed);
    }

    /** Seats nobody has claimed yet. */
    public int openSeats(long gameId) {
        int open = 0;
        for (GameRepository.Seat s : playerSeats(gameId)) if (s.accountId() == null) open++;
        return open;
    }

    /** Every seat a person or a bot could hold: the deity's own country is not one (issue #128). */
    public List<GameRepository.Seat> playerSeats(long gameId) {
        List<GameRepository.Seat> out = new ArrayList<>();
        for (GameRepository.Seat s : games.seats(gameId)) if (!"deity".equals(s.controller())) out.add(s);
        return out;
    }

    /**
     * Ring the starting bell (issue #123): the game leaves {@code setup}, and the first update is
     * scheduled from now. Until this happens the scheduler ignores the game and every command is
     * refused, so nobody gains anything by joining early.
     */
    private void ring(Game g, String why) {
        g.status = "running";
        games.setStatus(g.id, "running");
        java.time.Instant next = g.intervalSeconds > 0 ? java.time.Instant.now().plusSeconds(g.intervalSeconds) : null;
        g.nextUpdateAt = next;
        games.setSchedule(g.id, g.intervalSeconds, next);
        log.info("game {} '{}' started — {}", g.id, g.name, why);
    }

    /** The deity's bell, for when somebody is not coming (issue #123). */
    public Summary start(long gameId, Account by) {
        if (!by.admin()) throw new SecurityException("deity only");
        Game g = get(gameId);
        if (!"setup".equals(g.status)) throw new IllegalArgumentException("this game has already started");
        g.lock.lock();
        try { ring(g, "started by the deity with " + openSeats(gameId) + " seats still open"); } finally { g.lock.unlock(); }
        return summary(g, by);
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
            case Command.RailLane r -> r.from();
            case Command.BuildShip b -> b.harbor();
            case Command.Sail s -> null;
            case Command.Load l -> null;
            case Command.Unload u -> null;
            case Command.Lane l -> null;
            case Command.Scrap s -> null;
            case Command.Fish f -> null;
            case Command.BreakSanctuary b -> null;
        };
        return at == null ? c.verb() : at.x() + "," + at.y();
    }

    public Country country(long gameId, int id) { return get(gameId).world.country(id); }
    public LogRepository.UpdateEntry lastUpdate(long gameId) { return logs.lastUpdate(gameId); }
}
