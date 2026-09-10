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
    /**
     * Built on first use (issue #89). The command executor makes a {@code Ctx} for a single lookup —
     * what a hop costs, whether a hold carries a commodity — and a {@link Ledger} is
     * {@code int[nSectors * nCom]}: 29 MB, allocated and thrown away, for every command a player
     * issues. Only the update's steps ever write one, so nothing allocates it until something does.
     */
    private Ledger led;

    /** Sector count, which used to be read off the ledger and does not need one. */
    public final int nSectors;
    public final int etus;
    public final long seed;
    /**
     * The six neighbours of every sector, flat: {@code neighbours[i * 6 + k]} is a sector index, or -1
     * where the world does not wrap and the hex has no neighbour there. This was a
     * {@code List<List<Coord>>} — six {@link Coord} objects and two list objects per sector, rebuilt for
     * every update. At a million sectors that was over 200 MB of garbage an update, more than the world
     * itself weighs (issue #77). Read it with {@link #neighbour}.
     */
    private final int[] neighbours;

    public static final int DIRS = 6;

    /**
     * Sector indices worth visiting, ascending (issue #87).
     *
     * <p>An update is O(map) doing O(owned) work: at 512x1024 with forty countries three updates in,
     * 350 sectors of 524,288 are owned and the rest are ocean and wilderness that every step already
     * skips with a {@code continue}. These lists let a step iterate what matters instead of walking
     * past half a million hexes ten times over.
     *
     * <p><b>Ascending order is load-bearing.</b> The build-up step spends a country's cash in canonical
     * sector order until the treasury runs dry, and the state hash follows from it, so visiting an
     * index list must reproduce the order a full walk would have. Sorted by construction: the scan runs
     * from 0 upward.
     *
     * <p>Derived once per update rather than carried on {@code Country}. The scan costs 1.13 ms — 0.13%
     * of an update — and a stored index would be a second source of truth for who owns what, needing to
     * be kept in step on explore, capture and abandonment and carried through save and load.
     */
    private int[] owned;

    /**
     * Sectors holding people. The population step keys on people rather than ownership, which is the
     * right predicate — population should follow people, not flags — and will matter when a sector can
     * hold people while changing hands.
     */
    private int[] populated;

    /** Sectors holding parcels in transit. Needed to clear a sector whose parcels have all left. */
    private int[] withHeld;

    /**
     * Unowned sectors that still have something happening: an abandoned sector whose efficiency is
     * rotting, or a sea hex carrying a bridge or an order for one. Both are bounded worklists, not the
     * map.
     */
    private int[] activeUnowned;

    /**
     * {@link #owned} and {@link #activeUnowned} merged, ascending — every sector an update can act on.
     *
     * <p>Two reasons it is one list rather than two loops. The build-up step spends a country's cash as
     * it walks, so running all the unowned sectors before all the owned ones would change which sector
     * gets the last of the treasury. And a rail hop may cross a bridge, which is a sea hex belonging to
     * nobody (#60), so anything indexed by "a sector that can pay mobility" has to include unowned sea
     * carrying track.
     */
    private int[] ownedOrActive;

    /** Sector index of {@code i}'s {@code k}th neighbour, or -1 if there is none. */
    public int neighbour(int i, int k) { return neighbours[i * DIRS + k]; }
    /** Sector type by designation, built once (issue #82): GameConfig.sectorType is a linear string scan. */
    private final java.util.Map<String, SectorTypeCfg> typeIndex;
    /** Work (work-unit·ETUs) spent in step 4, subtracted from step 5's pool. Lazy, like the ledger. */
    private double[] workSpent;
    /** Ships as this update leaves them (the ship step rewrites this list; apply copies it out). */
    public final List<Ship> ships;
    /** Contacts as this update leaves them (the detection step rewrites this list; apply copies it out). */
    public final List<Contact> contacts;

    public Ctx(World snap, GameConfig cfg, Commodities com, long seed) {
        this.snap = snap; this.cfg = cfg; this.com = com; this.seed = seed;
        this.etus = cfg.etus();
        this.nSectors = snap.sectors().size();
        this.ships = new ArrayList<>(snap.ships());
        this.contacts = new ArrayList<>(snap.contacts());
        this.typeIndex = new java.util.HashMap<>();
        for (SectorTypeCfg t : cfg.economy().sectorTypes()) typeIndex.put(t.id(), t);
        int n = snap.sectors().size();


        // Direction-indexed, -1 where there is none. Iterating 0..5 and skipping -1 visits the same
        // neighbours in the same order as walking the packed list Hex.neighbours used to return.
        this.neighbours = org.hastingtx.empire.engine.geo.NeighbourTable.of(snap);

    }

    /**
     * Scratch for a graph search over the map, reused across calls (issue #87).
     *
     * <p>{@link org.hastingtx.empire.engine.update.steps.FlowStep#path} allocated three arrays the size
     * of the world every time it ran, and it runs once per plan — four hundred times in an update at
     * 512x1024, which is 2.6 GB allocated and filled to search a few hundred owned sectors. The arrays
     * live here instead, and each search resets only the entries it touched: the search never leaves
     * its owner's territory, so that is a few hundred writes rather than a million and a half.
     *
     * <p>One of these belongs to one search at a time. The update is sequential today; a nation-sharded
     * update would hold one per thread.
     */
    public static final class PathScratch {
        public final double[] dist;
        public final int[] prev;
        public final boolean[] done;
        private final int[] touched;
        private int nTouched;

        PathScratch(int n) {
            dist = new double[n];
            prev = new int[n];
            done = new boolean[n];
            touched = new int[n];
            java.util.Arrays.fill(dist, Double.POSITIVE_INFINITY);
            java.util.Arrays.fill(prev, -1);
        }

        /**
         * Record that {@code i} has been written, so {@link #reset} knows to put it back. Call this
         * exactly once per node, on its first write — the list is sized for one entry per sector, and
         * recording a node again on every edge relaxation would overrun it in a world one country owns
         * outright.
         */
        public void touch(int i) { touched[nTouched++] = i; }

        /** Back to all-infinite, all-unvisited, in time proportional to what the search actually saw. */
        public void reset() {
            for (int k = 0; k < nTouched; k++) { int i = touched[k]; dist[i] = Double.POSITIVE_INFINITY; prev[i] = -1; done[i] = false; }
            nTouched = 0;
        }
    }

    private PathScratch pathScratch;

    /** The shared search scratch, cleared and ready. */
    public PathScratch pathScratch() {
        if (pathScratch == null) pathScratch = new PathScratch(snap.sectors().size());
        return pathScratch;
    }


    // --- built on demand ------------------------------------------------------------------------
    //
    // A Ctx is made for every command as well as for every update, and a command needs none of this.
    // The update is single-threaded, so plain lazy fields are safe; a nation-sharded update would build
    // these once up front instead.

    /** The ledger this update is accumulating. */
    public Ledger led() {
        if (led == null) led = new Ledger(snap, com.size());
        return led;
    }

    /** This update's stock delta for commodity {@code c} in sector {@code i}, or 0 outside an update. */
    private int delta(int i, int c) { return led == null ? 0 : led.st(i, c); }

    public double[] workSpent() {
        if (workSpent == null) workSpent = new double[snap.sectors().size()];
        return workSpent;
    }

    public int[] owned() { indexes(); return owned; }
    public int[] populated() { indexes(); return populated; }
    public int[] withHeld() { indexes(); return withHeld; }
    public int[] activeUnowned() { indexes(); return activeUnowned; }
    public int[] ownedOrActive() { indexes(); return ownedOrActive; }

    private void indexes() {
        if (owned != null) return;
        int n = snap.sectors().size();
            // One ascending pass builds every worklist (issue #87). Ascending is what keeps the update
            // deterministic: a step iterating these visits sectors in the order a full walk would have.
            int nOwned = 0, nPop = 0, nHeld = 0, nActive = 0, nBoth = 0;
            int[] ownedBuf = new int[n], popBuf = new int[n], heldBuf = new int[n], activeBuf = new int[n], bothBuf = new int[n];
            int civ = com.civ, mil = com.mil, uw = com.uw;
            for (int i = 0; i < n; i++) {
                Sector s = snap.sectors().get(i);
                boolean own = s.owned();
                if (own) ownedBuf[nOwned++] = i;
                if (s.stock().get(civ) + s.stock().get(mil) + s.stock().get(uw) > 0) popBuf[nPop++] = i;
                if (!s.held().isEmpty()) heldBuf[nHeld++] = i;
                // an abandoned sector still rotting, or a sea hex with a bridge on it or ordered
                boolean active = !own && (s.efficiency() > 0 || (s.isOcean() && (s.railLevel() > 0 || s.railTarget() > 0)));
                if (active) activeBuf[nActive++] = i;
                if (own || active) bothBuf[nBoth++] = i;
            }
            this.owned = java.util.Arrays.copyOf(ownedBuf, nOwned);
            this.populated = java.util.Arrays.copyOf(popBuf, nPop);
            this.withHeld = java.util.Arrays.copyOf(heldBuf, nHeld);
            this.activeUnowned = java.util.Arrays.copyOf(activeBuf, nActive);
            this.ownedOrActive = java.util.Arrays.copyOf(bothBuf, nBoth);
    }

    public int idx(Coord c) { return snap.index(c); }
    public Sector sector(int i) { return snap.sectors().get(i); }
    public Country country(int id) { return snap.countries().get(id); }
    public SectorTypeCfg type(Sector s) {
        SectorTypeCfg t = typeIndex.get(s.designation());
        if (t == null) throw new IllegalArgumentException("unknown sector type: " + s.designation());
        return t;
    }

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
        double civ = s.stock().get(com.civ) + delta(i, com.civ);
        double uw = s.stock().get(com.uw) + delta(i, com.uw);
        double mil = s.stock().get(com.mil) + delta(i, com.mil);
        double raw = Math.max(0, civ) * w.perCiv() + Math.max(0, uw) * w.perUw() + Math.max(0, mil) * w.perMil();
        return raw * etus * w.happinessEffectCurve().eval(happiness) - workSpent()[i];
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
            for (int k = 0; k < DIRS; k++) {
                int v = neighbour(u, k);
                if (v < 0 || prev[v] != -2 || !railHop(v, owner)) continue;
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
        while (!q.isEmpty()) { int u = q.poll(); for (int k = 0; k < DIRS; k++) { int v = neighbour(u, k); if (v >= 0 && !seen.contains(v) && railHop(v, owner)) { seen.add(v); q.add(v); } } }
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
