package org.hastingtx.empire.engine.gen;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.config.PlayersCfg;
import org.hastingtx.empire.engine.config.WorldCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Rng;

import java.util.*;

/**
 * Fairland-style generator: one island per country grown from its capital, extra islands
 * to reach the land fraction, then terrain, elevation and resources. Deterministic in
 * (config, seed, country names).
 */
public final class WorldGenerator {
    private final GameConfig cfg;
    private final Commodities com;

    public WorldGenerator(GameConfig cfg) {
        this.cfg = cfg;
        this.com = Commodities.of(cfg);
    }

    public World generate(List<String> countryNames) { return generate(countryNames, cfg.world().seed()); }

    public World generate(List<String> countryNames, long seed) {
        WorldCfg wc = cfg.world();
        int w = wc.width(), h = wc.height(), n = countryNames.size();
        if (n > cfg.players().maxCountries()) throw new IllegalArgumentException("more countries than players.max_countries");
        if (wc.wrapY() && (h & 1) == 1) throw new IllegalArgumentException("world.height must be even when wrap_y is true");

        SplittableRandom rng = Rng.stream("worldgen", seed);
        Terrain[] terrain = new Terrain[w * h];
        Arrays.fill(terrain, Terrain.OCEAN);
        World dims = blankWorld(w, h, wc.wrapX(), wc.wrapY());

        // 1. capitals
        List<Coord> capitals = placeCapitals(dims, n, wc.terrain().minDistanceBetweenCapitals(), rng);

        // 2. islands from capitals
        int islandSize = Math.max(3, wc.terrain().islandSize());
        for (Coord c : capitals) growIsland(dims, terrain, c, islandSize, rng);

        // 3. extra islands to reach land_fraction
        int targetLand = (int) Math.round(wc.terrain().landFraction() * w * h);
        int extra = Math.max(0, (targetLand - n * islandSize) / islandSize);
        for (int i = 0; i < extra; i++) {
            Coord seedAt = null;
            for (int tries = 0; tries < 200 && seedAt == null; tries++) {
                Coord c = new Coord(rng.nextInt(w), rng.nextInt(h));
                if (terrain[dims.index(c)] != Terrain.OCEAN) continue;
                boolean farEnough = true;
                for (Coord cap : capitals) if (Hex.distance(dims, c, cap) < Math.max(3, wc.terrain().minDistanceBetweenCapitals() / 2)) { farEnough = false; break; }
                if (farEnough) seedAt = c;
            }
            if (seedAt != null) growIsland(dims, terrain, seedAt, islandSize, rng);
        }

        // 4. land terrain types, elevation, resources
        List<Sector> sectors = new ArrayList<>(w * h);
        Set<Coord> capitalSet = new HashSet<>(capitals);
        int[] sea = seaFertility(w, h, rng);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            Coord c = new Coord(x, y);
            Terrain t = terrain[dims.index(c)];
            if (t != Terrain.OCEAN) t = capitalSet.contains(c) ? Terrain.PLAINS : pickLandType(rng);
            int elev = elevation(t, rng);
            Resources res = t == Terrain.OCEAN ? new Resources(sea[dims.index(c)], 0, 0, 0, 0) : resources(t, rng);
            sectors.add(Sector.blank(c, t, elev, res, com.size()));
        }
        World world = new World(w, h, wc.wrapX(), wc.wrapY(), sectors, List.of(), List.of(), 0);

        // 5. countries and sanctuaries
        List<Country> countries = new ArrayList<>();
        PlayersCfg pc = cfg.players();
        for (int i = 0; i < n; i++) {
            Coord cap = capitals.get(i);
            HandicapCfg hc = handicapFor(countryNames.get(i)).resolve(cfg.handicapDefaults());
            Levels lv = new Levels(level("tech"), level("research"), level("education"), level("happiness"));
            countries.add(new Country(i, countryNames.get(i), cap, pc.startingCash(), cfg.economy().btu().start(), lv, hc, cfg.options().sanctuary(), false, 0));

            Coord second = null;
            for (Coord nb : Hex.neighbours(world, cap)) if (world.sector(nb).terrain().isLand() && !world.sector(nb).owned()) { second = nb; break; }
            if (second == null) { // force a neighbour to land
                second = Hex.neighbours(world, cap).get(0);
                Sector s = world.sector(second);
                world = world.withSector(s.withTerrain(Terrain.PLAINS, elevation(Terrain.PLAINS, rng), resources(Terrain.PLAINS, rng)));
            }
            double scale = hc.startingCommodities();
            world = world.withSector(seed(world.sector(cap), i, "capital", pc.startingCommodities().capital(), scale, pc));
            world = world.withSector(seed(world.sector(second), i, "sanctuary", pc.startingCommodities().sanctuary(), scale, pc));
        }
        return world.withCountries(countries);
    }

    /**
     * Seat the deity's own country at absolute {@code 0,0} (issue #128). POGO in the original.
     *
     * <p>The origin is the whole point. Every coordinate a player sees is relativised against their
     * capital, so a country whose capital <em>is</em> the origin reads the world in the same numbers
     * the database uses — the deity gets absolute coordinates out of the ordinary rule rather than
     * out of a special case. Its two sectors are sanctuary, which already means invisible,
     * invulnerable and unable to act beyond themselves, so the deity's presence costs the players
     * nothing.
     *
     * <p>Unlike {@link #addCountry} this ignores the minimum distance between capitals: the deity
     * goes at the origin or nowhere, and a country that cannot act is no threat to a neighbour.
     */
    public World addDeity(World world, String name, long seed) {
        for (Country c : world.countries())
            if (c.name().equalsIgnoreCase(name)) throw new IllegalArgumentException("this game already has a " + c.name());
        SplittableRandom rng = Rng.stream("deity:" + name, seed);
        Coord origin = new Coord(0, 0);
        if (world.sector(origin).owned())
            throw new IllegalArgumentException("0,0 already belongs to " + world.country(world.sector(origin).owner()).name());

        // the origin becomes land if it was sea; the deity's island is two hexes and goes nowhere
        world = world.withSector(world.sector(origin).withTerrain(Terrain.PLAINS, elevation(Terrain.PLAINS, rng), resources(Terrain.PLAINS, rng)));
        Coord second = null;
        for (Coord nb : Hex.neighbours(world, origin))
            if (!world.sector(nb).owned()) { second = nb; break; }
        if (second == null) throw new IllegalStateException("every hex around 0,0 is owned; there is no room for the deity");
        if (!world.sector(second).terrain().isLand())
            world = world.withSector(world.sector(second).withTerrain(Terrain.PLAINS, elevation(Terrain.PLAINS, rng), resources(Terrain.PLAINS, rng)));

        PlayersCfg pc = cfg.players();
        int id = world.countries().size();
        Levels lv = new Levels(level("tech"), level("research"), level("education"), level("happiness"));
        List<Country> countries = new ArrayList<>(world.countries());
        // always in sanctuary, whatever the preset says about players: the deity does not play
        countries.add(new Country(id, name, origin, pc.startingCash(), cfg.economy().btu().start(), lv,
                HandicapCfg.NONE.resolve(cfg.handicapDefaults()), true, false, 0));

        world = world.withSector(seed(world.sector(origin), id, "capital", pc.startingCommodities().capital(), 1.0, pc).withSanctuary(true));
        world = world.withSector(seed(world.sector(second), id, "sanctuary", pc.startingCommodities().sanctuary(), 1.0, pc).withSanctuary(true));
        return world.withCountries(countries);
    }

    /**
     * Seat a new country in a world that is already being played (issue #113). Deterministic in
     * (config, world, name, seed), and pure like the rest of the generator — the caller persists.
     *
     * <p>The capital goes on unowned land at least {@code min_distance_between_capitals} from every
     * existing capital. When the world has no such sector it refuses, and says which of the two
     * reasons applies and by how much — a latecomer takes the map as it is, and a world with no room
     * left is a fact for the deity to act on rather than something to paper over by raising land
     * under a player who has been sailing that water.
     */
    public World addCountry(World world, String name, long seed) {
        return addCountry(world, name, seed, cfg.players().maxCountries());
    }

    /**
     * As above, with the ceiling supplied by the caller. The engine counts countries; it does not know
     * that one of them may be the deity's (issue #128), which is server-side bookkeeping by design.
     * A caller that has seated a deity passes a limit that allows for it.
     */
    public World addCountry(World world, String name, long seed, int limit) {
        PlayersCfg pc = cfg.players();
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a country needs a name");
        String trimmed = name.trim();
        if (world.countries().size() >= limit)
            throw new IllegalArgumentException("this game is full at " + limit + " countries");
        for (Country c : world.countries())
            if (c.name().equalsIgnoreCase(trimmed))
                throw new IllegalArgumentException("a country called " + c.name() + " is already in this game");

        SplittableRandom rng = Rng.stream("addcountry:" + trimmed + ":" + world.countries().size(), seed);
        int minDist = Math.max(1, cfg.world().terrain().minDistanceBetweenCapitals());

        Coord cap = vacantLand(world, minDist, rng);
        if (cap == null) throw new IllegalArgumentException(noRoom(world, trimmed, minDist));

        // the capital is plains, as at generation, so nobody starts on a mountain
        world = world.withSector(world.sector(cap).withTerrain(Terrain.PLAINS, elevation(Terrain.PLAINS, rng), resources(Terrain.PLAINS, rng)));

        Coord second = null;
        for (Coord nb : Hex.neighbours(world, cap))
            if (world.sector(nb).terrain().isLand() && !world.sector(nb).owned()) { second = nb; break; }
        if (second == null) {   // force a neighbour to land, exactly as generation does
            second = Hex.neighbours(world, cap).get(0);
            world = world.withSector(world.sector(second).withTerrain(Terrain.PLAINS, elevation(Terrain.PLAINS, rng), resources(Terrain.PLAINS, rng)));
        }

        int id = world.countries().size();
        HandicapCfg hc = handicapFor(trimmed).resolve(cfg.handicapDefaults());
        Levels lv = new Levels(level("tech"), level("research"), level("education"), level("happiness"));
        List<Country> countries = new ArrayList<>(world.countries());
        countries.add(new Country(id, trimmed, cap, pc.startingCash(), cfg.economy().btu().start(), lv, hc, cfg.options().sanctuary(), false, 0));

        double scale = hc.startingCommodities();
        world = world.withSector(seed(world.sector(cap), id, "capital", pc.startingCommodities().capital(), scale, pc));
        world = world.withSector(seed(world.sector(second), id, "sanctuary", pc.startingCommodities().sanctuary(), scale, pc));
        return world.withCountries(countries);
    }

    /**
     * Why there was nowhere to put this capital, counted rather than asserted: the two reasons a land
     * sector can be unavailable are that somebody owns it or that it sits too close to an existing
     * capital, and which one dominates tells the deity what to change.
     */
    private String noRoom(World world, String name, int minDist) {
        long land = 0, owned = 0, tooClose = 0;
        for (Sector s : world.sectors()) {
            if (!s.terrain().isLand()) continue;
            land++;
            if (s.owned()) owned++;
            else if (!farEnough(world, s.at(), minDist)) tooClose++;
        }
        StringBuilder b = new StringBuilder("nowhere to seat ").append(name).append(" on this ")
                .append(world.width()).append('x').append(world.height()).append(" world: of ").append(land)
                .append(" land sectors, ").append(owned).append(" are owned and ").append(tooClose)
                .append(" are unowned but within ").append(minDist).append(" sectors of an existing capital");
        if (tooClose > 0 && owned < land)
            b.append(". Lowering the minimum distance between capitals would free ").append(tooClose).append(" of them");
        else
            b.append(". Every land sector is taken, so a new country needs a bigger world");
        return b.toString();
    }

    /** Unowned land far enough from every existing capital, or null if the world has none. */
    private Coord vacantLand(World world, int minDist, SplittableRandom rng) {
        List<Coord> ok = new ArrayList<>();
        for (Sector s : world.sectors()) {
            if (!s.terrain().isLand() || s.owned()) continue;
            if (farEnough(world, s.at(), minDist)) ok.add(s.at());
        }
        return ok.isEmpty() ? null : ok.get(rng.nextInt(ok.size()));
    }

    private boolean farEnough(World world, Coord c, int minDist) {
        for (Country o : world.countries()) if (Hex.distance(world, c, o.capital()) < minDist) return false;
        return true;
    }

    private Sector seed(Sector s, int owner, String designation, Map<String, Double> stocks, double scale, PlayersCfg pc) {
        double[] q = com.fromMap(stocks);
        for (int i = 0; i < q.length; i++) q[i] *= scale;
        return s.withOwner(owner)
                .withDesignation(designation, pc.startingEfficiency())
                .withMobility(pc.startingMobility())
                .withStock(Stocks.of(q))
                .withSanctuary(cfg.options().sanctuary());
    }

    private HandicapCfg handicapFor(String name) {
        if (cfg.players().countries() != null)
            for (PlayersCfg.CountryCfg c : cfg.players().countries())
                if (c.name().equals(name) && c.handicap() != null) return c.handicap();
        return HandicapCfg.NONE;
    }

    private double level(String id) { return cfg.players().startingLevels().getOrDefault(id, 0.0); }

    private static World blankWorld(int w, int h, boolean wx, boolean wy) {
        List<Sector> s = new ArrayList<>(w * h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) s.add(Sector.blank(new Coord(x, y), Terrain.OCEAN, 0, Resources.NONE, 1));
        return new World(w, h, wx, wy, s, List.of(), List.of(), 0);
    }

    private List<Coord> placeCapitals(World dims, int n, int minDist, SplittableRandom rng) {
        List<Coord> out = new ArrayList<>();
        int tries = 0;
        while (out.size() < n) {
            if (++tries > 20000) throw new IllegalStateException("cannot place " + n + " capitals at min distance " + minDist + " on " + dims.width() + "x" + dims.height());
            Coord c = new Coord(rng.nextInt(dims.width()), rng.nextInt(dims.height()));
            boolean ok = true;
            for (Coord o : out) if (Hex.distance(dims, c, o) < minDist) { ok = false; break; }
            if (ok) out.add(c);
        }
        return out;
    }

    private void growIsland(World dims, Terrain[] terrain, Coord seed, int size, SplittableRandom rng) {
        List<Coord> frontier = new ArrayList<>();
        int made = 0;
        if (terrain[dims.index(seed)] == Terrain.OCEAN) { terrain[dims.index(seed)] = Terrain.WILDERNESS; made++; }
        frontier.add(seed);
        int spike = Math.max(0, Math.min(100, cfg.world().terrain().spike()));
        while (made < size && !frontier.isEmpty()) {
            // spike: high values pick the newest frontier cell (spiky), low values pick uniformly (round)
            int idx = rng.nextInt(100) < spike ? frontier.size() - 1 : rng.nextInt(frontier.size());
            Coord c = frontier.get(idx);
            List<Coord> ocean = new ArrayList<>();
            for (Coord nb : Hex.neighbours(dims, c)) if (terrain[dims.index(nb)] == Terrain.OCEAN) ocean.add(nb);
            if (ocean.isEmpty()) { frontier.remove(idx); continue; }
            Coord pick = ocean.get(rng.nextInt(ocean.size()));
            terrain[dims.index(pick)] = Terrain.WILDERNESS;
            frontier.add(pick);
            made++;
        }
    }

    private Terrain pickLandType(SplittableRandom rng) {
        Map<String, Double> mix = cfg.world().terrain().landMix();
        double total = 0; for (double v : mix.values()) total += v;
        double u = rng.nextDouble() * total;
        List<String> keys = new ArrayList<>(mix.keySet()); Collections.sort(keys);
        for (String k : keys) { u -= mix.get(k); if (u <= 0) return Terrain.of(k); }
        return Terrain.of(keys.get(keys.size() - 1));
    }

    /**
     * Fishing grounds (issue #56): the sea is fertile by region — one triangular draw per block of
     * region_size² hexes, jittered per hex — so boats have somewhere worth going. Deterministic from the rng.
     */
    public int[] seaFertility(int w, int h, SplittableRandom rng) {
        WorldCfg.SeaFertilityCfg sf = cfg.world().resources().seaFertility();
        int[] out = new int[w * h];
        if (sf == null) return out;
        int rs = Math.max(1, sf.regionSize());
        int rw = (w + rs - 1) / rs, rh = (h + rs - 1) / rs;
        int[] region = new int[rw * rh];
        for (int i = 0; i < region.length; i++) region[i] = (int) Math.round(Rng.triangular(rng, sf.triangular().get(0), sf.triangular().get(1), sf.triangular().get(2)));
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int base = region[(y / rs) * rw + (x / rs)];
            int j = sf.jitter() > 0 ? rng.nextInt(2 * sf.jitter() + 1) - sf.jitter() : 0;
            out[y * w + x] = Math.max(0, Math.min(100, base + j));
        }
        return out;
    }

    private int elevation(Terrain t, SplittableRandom rng) {
        WorldCfg.Range r = cfg.world().elevationByTerrain().get(t.id());
        if (r == null) return 0;
        return r.min() + rng.nextInt(Math.max(1, r.max() - r.min() + 1));
    }

    private Resources resources(Terrain t, SplittableRandom rng) {
        WorldCfg.ResourcesCfg rc = cfg.world().resources();
        return new Resources(tri(rc.fertility(), t, rng), tri(rc.minerals(), t, rng), tri(rc.gold(), t, rng), tri(rc.oil(), t, rng), tri(rc.uranium(), t, rng));
    }

    private static int tri(Map<String, List<Integer>> table, Terrain t, SplittableRandom rng) {
        List<Integer> p = table.get(t.id());
        if (p == null) return 0;
        return (int) Math.round(Rng.triangular(rng, p.get(0), p.get(1), p.get(2)));
    }
}
