package org.hastingtx.empire.engine.model;

/**
 * A plane (issue #262, #71 slice 3a): one aircraft of a class in {@code units.planes}, sitting on an airfield of yours.
 * {@code tech} is the level it was built at (KNOWN pln_tech); {@code efficiency} is its condition, and below the
 * class minimum it is wreckage. A plane carries nothing between sorties: it takes its petrol and its bombs from the
 * field each time it flies, as the original's {@code pln_equip} does.
 *
 * <p>{@code note} is what it did last, for the reply and the panel.
 */
public record Plane(long id, int owner, String cls, Coord at, double efficiency, double tech, long built, String note) {

    public Plane withAt(Coord c) { return new Plane(id, owner, cls, c, efficiency, tech, built, note); }
    public Plane withEfficiency(double e) { return new Plane(id, owner, cls, at, Math.max(0, Math.min(100, e)), tech, built, note); }
    public Plane withOwner(int o) { return new Plane(id, o, cls, at, efficiency, tech, built, note); }
    public Plane withNote(String n) { return new Plane(id, owner, cls, at, efficiency, tech, built, n); }
}
