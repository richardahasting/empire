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
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            Coord c = new Coord(x, y);
            Terrain t = terrain[dims.index(c)];
            if (t != Terrain.OCEAN) t = capitalSet.contains(c) ? Terrain.PLAINS : pickLandType(rng);
            int elev = elevation(t, rng);
            Resources res = t == Terrain.OCEAN ? Resources.NONE : resources(t, rng);
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
