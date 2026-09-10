package org.hastingtx.empire.engine.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One hex. Immutable; use the with* methods. {@code owner} is -1 for unowned.
 * {@code designation} is a sector-type id from config ("wilderness" when undesignated).
 * {@code deliver} holds the standing delivery orders (issue #45).
 *
 * <h2>Storage (issue #77)</h2>
 * A record of eighteen wide components became a class of narrow fields, because Empire's quantities are
 * discrete and its scales are 0–100: the decimal place of an efficiency was never visible to a player,
 * and a pointer to a {@code Coord} was four bytes plus a twenty-four byte object to hold two numbers
 * that fit in four. The accessors are unchanged and still return {@code int}/{@code double}, so the
 * update steps, the views and the repository did not move (rule 6 on the issue).
 *
 * <p>Narrowing rounds half-up ({@link Math#round}) and clamps to the field's range. Nothing accrues in
 * a fraction that rounding could swallow: every producer of a level — {@code BuildUpStep}, decay,
 * capture damage — now emits whole points, so the value stored is the value computed.
 *
 * <p>{@code at} and {@code distCenter} are held as two shorts each and a {@link Coord} is built on
 * demand. A world is at most 2048×1024, so the coordinates fit with room to spare, and escape analysis
 * removes most of the allocations. {@code distCenter} uses {@link #NO_COORD} for "unset" rather than a
 * null reference.
 *
 * <p>An unset threshold is {@link #NO_THRESHOLD} rather than {@code NaN}: a short has no NaN. Sectors
 * with no thresholds at all share one canonical array, which is most of a large world.
 */
public final class Sector {

    public static final int NOBODY = -1;
    /** {@code distCenter} sentinel: no distribution centre set. */
    static final short NO_COORD = Short.MIN_VALUE;
    /** {@code thresholds} sentinel: no threshold set for this commodity. */
    static final short NO_THRESHOLD = -1;

    // position, 4 B — a Coord is materialised on demand
    private final short x, y;
    // terrain and the land under it, 3 B
    private final byte terrain;
    private final short elevation;
    // endowments, 0..100, 5 B
    private final byte fertility, minerals, gold, oil, uranium;
    // tenure, 2 B
    private final byte owner, designation;
    // 0..100 scales (mobility 0..127), 7 B
    private final byte efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget;
    private final boolean sanctuary;
    // distribution centre, 4 B
    private final short distX, distY;
    // references, 16 B
    private final short[] thresholds;
    private final Stocks stock;
    private final List<HeldParcel> held;
    private final DeliverOrders deliver;

    private Sector(short x, short y, byte terrain, short elevation,
                   byte fertility, byte minerals, byte gold, byte oil, byte uranium,
                   byte owner, byte designation,
                   byte efficiency, byte mobility, byte roadLevel, byte railLevel, byte radarLevel,
                   byte roadTarget, byte railTarget, boolean sanctuary,
                   short distX, short distY,
                   short[] thresholds, Stocks stock, List<HeldParcel> held, DeliverOrders deliver) {
        this.x = x; this.y = y; this.terrain = terrain; this.elevation = elevation;
        this.fertility = fertility; this.minerals = minerals; this.gold = gold; this.oil = oil; this.uranium = uranium;
        this.owner = owner; this.designation = designation;
        this.efficiency = efficiency; this.mobility = mobility; this.roadLevel = roadLevel; this.railLevel = railLevel;
        this.radarLevel = radarLevel; this.roadTarget = roadTarget; this.railTarget = railTarget; this.sanctuary = sanctuary;
        this.distX = distX; this.distY = distY;
        this.thresholds = thresholds; this.stock = stock; this.held = held; this.deliver = deliver;
    }

    // --- narrowing -------------------------------------------------------------------------------

    /** Half-up (2.5 -> 3), clamped. Rule 2 on issue #77. */
    static byte scale(double v, int lo, int hi) {
        long r = Math.round(v);
        if (r < lo) r = lo;
        if (r > hi) r = hi;
        return (byte) r;
    }

    private static short coord(int v) { return (short) v; }

    private static short elev(int v) {
        if (v < Short.MIN_VALUE + 1) return Short.MIN_VALUE + 1;
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        return (short) v;
    }

    private static byte country(int o) {
        if (o == NOBODY) return -1;
        if (o < 0 || o > 126) throw new IllegalArgumentException("country id out of range for a byte: " + o);
        return (byte) o;
    }

    /** A threshold is an exact quantity, not a 0..100 scale — a byte would make {@code thresh food 100} mean 120. */
    static short thresholdOf(double v) {
        if (Double.isNaN(v)) return NO_THRESHOLD;
        long r = Math.round(v);
        if (r < 0) return 0;
        if (r > Short.MAX_VALUE) return Short.MAX_VALUE;
        return (short) r;
    }

    // --- accessors, unchanged --------------------------------------------------------------------

    public Coord at() { return new Coord(x, y); }
    public Terrain terrain() { return Terrain.byOrdinal(terrain); }
    public int elevation() { return elevation; }
    public Resources resources() { return new Resources(fertility, minerals, gold, oil, uranium); }
    public int owner() { return owner; }
    public String designation() { return Designations.name(designation); }
    public double efficiency() { return efficiency; }
    public double mobility() { return mobility; }
    public Stocks stock() { return stock; }
    public Coord distCenter() { return distX == NO_COORD ? null : new Coord(distX, distY); }
    public double roadLevel() { return roadLevel; }
    public double railLevel() { return railLevel; }
    public double radarLevel() { return radarLevel; }
    public List<HeldParcel> held() { return held; }
    public boolean sanctuary() { return sanctuary; }
    public double roadTarget() { return roadTarget; }
    public double railTarget() { return railTarget; }
    public DeliverOrders deliver() { return deliver; }

    /** The thresholds as the rest of the code has always seen them: NaN where none is set. */
    public double[] thresholds() {
        double[] out = new double[thresholds.length];
        for (int i = 0; i < out.length; i++) out[i] = thresholds[i] == NO_THRESHOLD ? Double.NaN : thresholds[i];
        return out;
    }

    public boolean owned() { return owner != NOBODY; }
    public boolean ownedBy(int c) { return owner == c; }
    public double threshold(int i) { return thresholds[i] == NO_THRESHOLD ? Double.NaN : thresholds[i]; }
    public boolean hasThreshold(int i) { return thresholds[i] != NO_THRESHOLD; }
    public int thresholdCount() { return thresholds.length; }
    public int heldCount() { return held.size(); }

    /** One endowment without building a {@link Resources} — the production and population steps read these per sector. */
    public int resource(String gate) {
        return switch (gate) {
            case "fertility" -> fertility;
            case "minerals" -> minerals;
            case "gold" -> gold;
            case "oil" -> oil;
            case "uranium" -> uranium;
            default -> throw new IllegalArgumentException("unknown resource gate: " + gate);
        };
    }

    public int fertility() { return fertility; }
    /** True without materialising a {@link Terrain} — {@code terrain().isLand()} in the hot paths. */
    public boolean isLand() { return terrain != Terrain.OCEAN.ordinal(); }
    public boolean isOcean() { return terrain == Terrain.OCEAN.ordinal(); }

    // --- withers ---------------------------------------------------------------------------------

    private Sector copy(byte owner, byte designation, byte efficiency, byte mobility, byte roadLevel, byte railLevel,
                        byte radarLevel, byte roadTarget, byte railTarget, boolean sanctuary, short distX, short distY,
                        short[] thresholds, Stocks stock, List<HeldParcel> held, DeliverOrders deliver) {
        return new Sector(x, y, terrain, elevation, fertility, minerals, gold, oil, uranium, owner, designation,
                efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary,
                distX, distY, thresholds, stock, held, deliver);
    }

    public Sector withOwner(int o) { return copy(country(o), designation, efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, thresholds, stock, held, deliver); }
    public Sector withDesignation(String d, double eff) { return copy(owner, Designations.id(d), scale(eff, 0, 100), mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, thresholds, stock, held, deliver); }
    public Sector withEfficiency(double e) { return copy(owner, designation, scale(e, 0, 100), mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, thresholds, stock, held, deliver); }
    public Sector withMobility(double m) { return copy(owner, designation, efficiency, scale(m, 0, 127), roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, thresholds, stock, held, deliver); }
    public Sector withStock(Stocks s) { return copy(owner, designation, efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, thresholds, s, held, deliver); }
    public Sector withDistCenter(Coord c) { return copy(owner, designation, efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, c == null ? NO_COORD : coord(c.x()), c == null ? NO_COORD : coord(c.y()), thresholds, stock, held, deliver); }
    public Sector withRadarLevel(double r) { return copy(owner, designation, efficiency, mobility, roadLevel, railLevel, scale(r, 0, 100), roadTarget, railTarget, sanctuary, distX, distY, thresholds, stock, held, deliver); }
    public Sector withRoadLevel(double r) { return copy(owner, designation, efficiency, mobility, scale(r, 0, 100), railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, thresholds, stock, held, deliver); }
    public Sector withRoadTarget(double t) { return copy(owner, designation, efficiency, mobility, roadLevel, railLevel, radarLevel, scale(t, 0, 100), railTarget, sanctuary, distX, distY, thresholds, stock, held, deliver); }
    public Sector withRailTarget(double t) { return copy(owner, designation, efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, scale(t, 0, 100), sanctuary, distX, distY, thresholds, stock, held, deliver); }
    public Sector withRailLevel(double r) { return copy(owner, designation, efficiency, mobility, roadLevel, scale(r, 0, 100), radarLevel, roadTarget, railTarget, sanctuary, distX, distY, thresholds, stock, held, deliver); }
    public Sector withHeld(List<HeldParcel> h) { return copy(owner, designation, efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, thresholds, stock, List.copyOf(h), deliver); }
    public Sector withSanctuary(boolean s) { return copy(owner, designation, efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, s, distX, distY, thresholds, stock, held, deliver); }
    public Sector withDeliver(DeliverOrders d) { return copy(owner, designation, efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, thresholds, stock, held, d); }

    public Sector withThresholds(double[] t) {
        short[] n = new short[t.length];
        boolean any = false;
        for (int i = 0; i < t.length; i++) { n[i] = thresholdOf(t[i]); if (n[i] != NO_THRESHOLD) any = true; }
        return copy(owner, designation, efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, any ? n : noThresholds(t.length), stock, held, deliver);
    }

    /** One threshold, without a round trip through a {@code double[]}. */
    public Sector withThreshold(int i, double v) {
        short s = thresholdOf(v);
        if (thresholds[i] == s) return this;
        short[] n = thresholds.clone();
        n[i] = s;
        boolean any = false;
        for (short q : n) if (q != NO_THRESHOLD) { any = true; break; }
        return copy(owner, designation, efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, any ? n : noThresholds(n.length), stock, held, deliver);
    }

    public Sector withTerrain(Terrain t, int elev, Resources r) {
        return new Sector(x, y, (byte) t.ordinal(), elev(elev), scale(r.fertility(), 0, 100), scale(r.minerals(), 0, 100),
                scale(r.gold(), 0, 100), scale(r.oil(), 0, 100), scale(r.uranium(), 0, 100), owner, designation,
                efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary,
                distX, distY, thresholds, stock, held, deliver);
    }

    public Sector withAt(Coord c) {
        return new Sector(coord(c.x()), coord(c.y()), terrain, elevation, fertility, minerals, gold, oil, uranium,
                owner, designation, efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget,
                sanctuary, distX, distY, thresholds, stock, held, deliver);
    }

    // --- construction ----------------------------------------------------------------------------

    /**
     * The all-unset threshold array for {@code n} commodities. A large world is mostly sectors nobody has
     * ever set a threshold on; they share one array instead of holding half a million copies of the same
     * fourteen sentinels.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, short[]> NO_THRESHOLDS = new java.util.concurrent.ConcurrentHashMap<>();

    static short[] noThresholds(int n) {
        return NO_THRESHOLDS.computeIfAbsent(n, k -> { short[] a = new short[k]; Arrays.fill(a, NO_THRESHOLD); return a; });
    }

    public static Sector blank(Coord at, Terrain t, int elevation, Resources r, int nCommodities) {
        return new Sector(coord(at.x()), coord(at.y()), (byte) t.ordinal(), elev(elevation),
                scale(r.fertility(), 0, 100), scale(r.minerals(), 0, 100), scale(r.gold(), 0, 100),
                scale(r.oil(), 0, 100), scale(r.uranium(), 0, 100),
                (byte) NOBODY, Designations.id("wilderness"),
                (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, false,
                NO_COORD, NO_COORD,
                noThresholds(nCommodities), Stocks.zero(nCommodities), List.of(), DeliverOrders.none(nCommodities));
    }

    // --- value semantics -------------------------------------------------------------------------

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Sector s)) return false;
        return x == s.x && y == s.y && terrain == s.terrain && elevation == s.elevation
                && fertility == s.fertility && minerals == s.minerals && gold == s.gold && oil == s.oil && uranium == s.uranium
                && owner == s.owner && designation == s.designation
                && efficiency == s.efficiency && mobility == s.mobility && roadLevel == s.roadLevel && railLevel == s.railLevel
                && radarLevel == s.radarLevel && roadTarget == s.roadTarget && railTarget == s.railTarget && sanctuary == s.sanctuary
                && distX == s.distX && distY == s.distY
                && Arrays.equals(thresholds, s.thresholds) && stock.equals(s.stock) && held.equals(s.held) && deliver.equals(s.deliver);
    }

    @Override public int hashCode() {
        int h = Objects.hash(x, y, terrain, elevation, fertility, minerals, gold, oil, uranium, owner, designation,
                efficiency, mobility, roadLevel, railLevel, radarLevel, roadTarget, railTarget, sanctuary, distX, distY, stock, held, deliver);
        return 31 * h + Arrays.hashCode(thresholds);
    }

    @Override public String toString() {
        return "Sector[" + at() + " " + terrain().id() + " owner=" + owner + " " + designation() + " eff=" + efficiency + "]";
    }
}
