package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;

import java.util.ArrayList;
import java.util.List;

/** Read-only snapshot plus derived lookups shared by all steps. */
public final class Ctx {
    public final World snap;
    public final GameConfig cfg;
    public final Commodities com;
    public final Ledger led;
    public final int etus;
    public final long seed;
    public final List<List<Coord>> neighbours;
    /** Work (work-unit·ETUs) spent in step 4, subtracted from step 5's pool. */
    public final double[] workSpent;
    /** Ships as this update leaves them (the ship step rewrites this list; apply copies it out). */
    public final List<Ship> ships;
    /** Contacts as this update leaves them (the detection step rewrites this list; apply copies it out). */
    public final List<Contact> contacts;

    public Ctx(World snap, GameConfig cfg, Commodities com, long seed) {
        this.snap = snap; this.cfg = cfg; this.com = com; this.seed = seed;
        this.etus = cfg.etus();
        this.led = new Ledger(snap, com.size());
        this.workSpent = new double[snap.sectors().size()];
        this.ships = new ArrayList<>(snap.ships());
        this.contacts = new ArrayList<>(snap.contacts());
        this.neighbours = new ArrayList<>(snap.sectors().size());
        for (Sector s : snap.sectors()) neighbours.add(Hex.neighbours(snap, s.at()));
    }

    public int idx(Coord c) { return snap.index(c); }
    public Sector sector(int i) { return snap.sectors().get(i); }
    public Country country(int id) { return snap.countries().get(id); }
    public SectorTypeCfg type(Sector s) { return cfg.sectorType(s.designation()); }

    /** Storage cap for a non-person commodity in this sector. GUESS: independent of efficiency. */
    public double capacity(Sector s, int c) {
        SectorTypeCfg t = type(s);
        if (t.capacity() != null) {
            Double cap = t.capacity().get(com.id(c));
            if (cap != null) return cap;
        }
        return cfg.economy().defaultCapacity() * t.storeMultiplierOr1();
    }

    /** Population ceiling for this sector. KNOWN: flat per type (big cities aside); RES_POP scales it by research. */
    public double maxPopulation(Sector s) {
        double research = s.owned() ? country(s.owner()).levels().research() : 0;
        var pop = cfg.economy().population();
        double effScale = Boolean.TRUE.equals(pop.maxPopScalesWithEfficiency()) ? s.efficiency() / 100.0 : 1.0;
        return type(s).maxPopulation() * effScale * pop.maxPopResearchCurve().eval(research);
    }

    /** Shipping weight of one unit of c leaving sector s (packing applies at >= 60% efficiency, KNOWN). */
    public double weightLeaving(int c, Sector s) {
        String cls = s.efficiency() >= 60 ? type(s).packingOrNormal() : "inefficient";
        return com.weight(c, cls);
    }

    /** Mobility cost per weight-unit to move INTO sector s (terrain × efficiency × road). */
    public double moveCostInto(Sector s) {
        EconomyCfg.MobilityCfg m = cfg.economy().mobility();
        Double base = m.moveCostByTerrain().get(s.terrain().id());
        if (base == null) return Double.POSITIVE_INFINITY;
        double eff = m.efficiencyDiscount().eval(s.efficiency());
        double road = cfg.infrastructure().road().mobilityDiscountCurve().eval(s.roadLevel());
        // rail is a road that is cheaper still (Richard 2026-09-09: "make rail easy, just like roads, but cheaper"): its level
        // discounts everything entering the sector on top of the road discount, no train orders needed (issue #60)
        var rc = cfg.infrastructure().rail().mobilityDiscountCurve();
        double rail = rc == null ? 1.0 : rc.eval(s.railLevel());
        return base * eff * road * rail;
    }

    public double workAvailable(Sector s) {
        EconomyCfg.WorkCfg w = cfg.economy().work();
        double happiness = s.owned() ? country(s.owner()).levels().happiness() : 0;
        double raw = s.stock().get(com.civ) * w.perCiv() + s.stock().get(com.uw) * w.perUw() + s.stock().get(com.mil) * w.perMil();
        return raw * w.happinessEffectCurve().eval(happiness);
    }

    /**
     * Post-population work pool for the whole update, in work-unit·ETUs: people after this
     * update's births and deaths, times the ETUs, times the happiness curve, minus step 4's spend.
     */
    public double workAvailablePost(int i) {
        Sector s = sector(i);
        EconomyCfg.WorkCfg w = cfg.economy().work();
        double happiness = s.owned() ? country(s.owner()).levels().happiness() : 0;
        double civ = s.stock().get(com.civ) + led.stock[i][com.civ];
        double uw = s.stock().get(com.uw) + led.stock[i][com.uw];
        double mil = s.stock().get(com.mil) + led.stock[i][com.mil];
        double raw = Math.max(0, civ) * w.perCiv() + Math.max(0, uw) * w.perUw() + Math.max(0, mil) * w.perMil();
        return raw * etus * w.happinessEffectCurve().eval(happiness) - workSpent[i];
    }

    // ---- rail network (KNOWN-by-spec: contiguous chain of rail-capable sectors between depots) ----
    public boolean railCapable(int i) {
        Sector s = sector(i);
        return (s.owned() && s.terrain().isLand() || s.terrain() == Terrain.OCEAN) && s.railLevel() >= cfg.infrastructure().rail().minLevelToCarry();
    }
    /** A rail hop {@code owner} may use: its own track, or a bridge (rail on the sea belongs to no one in phase 1; issue #60). */
    public boolean railHop(int i, int owner) { Sector s = sector(i); return railCapable(i) && (s.terrain() == Terrain.OCEAN || s.owner() == owner); }
    public boolean isDepot(int i) {
        Sector s = sector(i);
        Double minEff = cfg.economy().efficiency().productionMinEfficiency();
        return type(s).hasFlag("rail_endpoint") && s.efficiency() >= (minEff == null ? 0 : minEff) && railCapable(i);
    }
    /** Fewest-hop path over rail-capable sectors of {@code owner}, or null. Deterministic (index order). */
    public java.util.List<Coord> railPath(Coord from, Coord to, int owner) {
        int src = idx(from), dst = idx(to);
        if (!railCapable(src) || !railCapable(dst) || sector(src).owner() != owner || sector(dst).owner() != owner) return null;
        int n = snap.sectors().size();
        int[] prev = new int[n]; java.util.Arrays.fill(prev, -2);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        q.add(src); prev[src] = -1;
        while (!q.isEmpty()) {
            int u = q.poll();
            if (u == dst) break;
            for (Coord nb : neighbours.get(u)) {
                int v = idx(nb);
                if (prev[v] != -2 || !railHop(v, owner)) continue;
                prev[v] = u; q.add(v);
            }
        }
        if (prev[dst] == -2) return null;
        java.util.LinkedList<Coord> out = new java.util.LinkedList<>();
        for (int v = dst; v != -1; v = prev[v]) out.addFirst(sector(v).at());
        return out;
    }
    /** Every rail-capable sector reachable from {@code from} for {@code owner} (for naming where a line breaks). */
    public java.util.Set<Integer> railReach(Coord from, int owner) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int src = idx(from);
        if (!railCapable(src) || sector(src).owner() != owner) return seen;
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>(); q.add(src); seen.add(src);
        while (!q.isEmpty()) { int u = q.poll(); for (Coord nb : neighbours.get(u)) { int v = idx(nb); if (!seen.contains(v) && railHop(v, owner)) { seen.add(v); q.add(v); } } }
        return seen;
    }
    /** Units a rail sector can carry this update: capacity_at_100 × rail_level/100. Depot efficiency is applied at the endpoints. */
    public double railCapacity(int i) {
        return cfg.infrastructure().rail().capacityPerUpdateAt100() * sector(i).railLevel() / 100.0;
    }

    public double curve(String id, double level) {
        var c = cfg.economy().curves().get(id);
        if (c == null) throw new IllegalStateException("unknown curve: " + id);
        return c.eval(level);
    }
}
