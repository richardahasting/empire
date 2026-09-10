package org.hastingtx.empire.engine.model;

/**
 * What one country remembers of one sector (issue #64).
 *
 * <p>Ships lifted the fog and the fog closed behind them again. The original kept a map of everything
 * ever seen, so a coastline you sailed past stays on your chart — as it was when you saw it, not as it
 * is now. This is that record: the sector as it looked, and when you looked.
 *
 * <p>It holds only what a passing look would tell you — terrain, who flew a flag over it, what it was
 * built as. Not its stock, not its mobility, not its roads. A memory that knew a warehouse's inventory
 * would be espionage, not cartography.
 */
public record SeenSector(int owner, Coord at, Terrain terrain, int sectorOwner, String designation, long seenUpdate) {

    /** Updates since this sector was last laid eyes on, as of {@code now}. */
    public long age(long now) { return now - seenUpdate; }
}
