package org.hastingtx.empire.engine.model;

/**
 * A land unit (issue #247, #71 slice 2): a body of soldiers and supplies of a class in {@code units.land}, standing in a
 * sector. {@code stock} is what it carries (its military among them, up to the class's capacities); {@code tech} is the
 * level it was laid at (KNOWN lnd_tech); {@code mobility} is its own pool (KNOWN lnd_mobil); {@code note} is what it did
 * last update.
 */
public record LandUnit(long id, int owner, String cls, Coord at, double efficiency, Stocks stock, double mobility, double tech, long built, String note) {
    public LandUnit withAt(Coord c) { return new LandUnit(id, owner, cls, c, efficiency, stock, mobility, tech, built, note); }
    public LandUnit withEfficiency(double e) { return new LandUnit(id, owner, cls, at, Math.max(0, Math.min(100, e)), stock, mobility, tech, built, note); }
    public LandUnit withStock(Stocks s) { return new LandUnit(id, owner, cls, at, efficiency, s, mobility, tech, built, note); }
    public LandUnit withMobility(double m) { return new LandUnit(id, owner, cls, at, efficiency, stock, m, tech, built, note); }
    public LandUnit withOwner(int o) { return new LandUnit(id, o, cls, at, efficiency, stock, mobility, tech, built, note); }
    public LandUnit withNote(String n) { return new LandUnit(id, owner, cls, at, efficiency, stock, mobility, tech, built, n); }
    public double load() { return stock.total(); }
}
