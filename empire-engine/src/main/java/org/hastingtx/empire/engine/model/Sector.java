package org.hastingtx.empire.engine.model;

import java.util.List;

/**
 * One hex. Immutable; use the with* methods. {@code owner} is -1 for unowned.
 * {@code designation} is a sector-type id from config ("wilderness" when undesignated).
 * {@code thresholds[i]} is NaN when no threshold is set for commodity i.
 */
public record Sector(
        Coord at,
        Terrain terrain,
        int elevation,
        Resources resources,
        int owner,
        String designation,
        double efficiency,
        double mobility,
        Stocks stock,
        double[] thresholds,
        Coord distCenter,
        double roadLevel,
        double railLevel,
        double radarLevel,
        List<HeldParcel> held,
        boolean sanctuary) {

    public static final int NOBODY = -1;

    public boolean owned() { return owner != NOBODY; }
    public boolean ownedBy(int c) { return owner == c; }
    public double threshold(int i) { return thresholds[i]; }
    public boolean hasThreshold(int i) { return !Double.isNaN(thresholds[i]); }
    public int heldCount() { return held.size(); }

    public Sector withOwner(int o) { return new Sector(at, terrain, elevation, resources, o, designation, efficiency, mobility, stock, thresholds, distCenter, roadLevel, railLevel, radarLevel, held, sanctuary); }
    public Sector withDesignation(String d, double eff) { return new Sector(at, terrain, elevation, resources, owner, d, eff, mobility, stock, thresholds, distCenter, roadLevel, railLevel, radarLevel, held, sanctuary); }
    public Sector withEfficiency(double e) { return new Sector(at, terrain, elevation, resources, owner, designation, e, mobility, stock, thresholds, distCenter, roadLevel, railLevel, radarLevel, held, sanctuary); }
    public Sector withMobility(double m) { return new Sector(at, terrain, elevation, resources, owner, designation, efficiency, m, stock, thresholds, distCenter, roadLevel, railLevel, radarLevel, held, sanctuary); }
    public Sector withStock(Stocks s) { return new Sector(at, terrain, elevation, resources, owner, designation, efficiency, mobility, s, thresholds, distCenter, roadLevel, railLevel, radarLevel, held, sanctuary); }
    public Sector withThresholds(double[] t) { return new Sector(at, terrain, elevation, resources, owner, designation, efficiency, mobility, stock, t, distCenter, roadLevel, railLevel, radarLevel, held, sanctuary); }
    public Sector withDistCenter(Coord c) { return new Sector(at, terrain, elevation, resources, owner, designation, efficiency, mobility, stock, thresholds, c, roadLevel, railLevel, radarLevel, held, sanctuary); }
    public Sector withRoadLevel(double r) { return new Sector(at, terrain, elevation, resources, owner, designation, efficiency, mobility, stock, thresholds, distCenter, r, railLevel, radarLevel, held, sanctuary); }
    public Sector withHeld(List<HeldParcel> h) { return new Sector(at, terrain, elevation, resources, owner, designation, efficiency, mobility, stock, thresholds, distCenter, roadLevel, railLevel, radarLevel, List.copyOf(h), sanctuary); }
    public Sector withSanctuary(boolean s) { return new Sector(at, terrain, elevation, resources, owner, designation, efficiency, mobility, stock, thresholds, distCenter, roadLevel, railLevel, radarLevel, held, s); }
    public Sector withTerrain(Terrain t, int elev, Resources r) { return new Sector(at, t, elev, r, owner, designation, efficiency, mobility, stock, thresholds, distCenter, roadLevel, railLevel, radarLevel, held, sanctuary); }
    public Sector withAt(Coord c) { return new Sector(c, terrain, elevation, resources, owner, designation, efficiency, mobility, stock, thresholds, distCenter, roadLevel, railLevel, radarLevel, held, sanctuary); }

    public static Sector blank(Coord at, Terrain t, int elevation, Resources r, int nCommodities) {
        double[] th = new double[nCommodities];
        java.util.Arrays.fill(th, Double.NaN);
        return new Sector(at, t, elevation, r, NOBODY, "wilderness", 0, 0, Stocks.zero(nCommodities), th, null, 0, 0, 0, List.of(), false);
    }
}
