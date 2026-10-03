package org.hastingtx.empire.engine.model;

/**
 * A plane (issue #262, #71 slice 3a): one aircraft of a class in {@code units.planes}, sitting on an airfield of yours.
 * {@code tech} is the level it was built at (KNOWN pln_tech); {@code efficiency} is its condition, and below the
 * class minimum it is wreckage. A plane carries nothing between sorties: it takes its petrol and its bombs from the
 * field each time it flies, as the original's {@code pln_equip} does.
 *
 * <p>{@code note} is what it did last, for the reply and the panel. {@code mission} is a standing order (issue #71;
 * KNOWN miss.c): {@link #AIR_DEFENCE} guards the sectors within {@code radius} of {@code opPoint}, or null for none.
 */
public record Plane(long id, int owner, String cls, Coord at, double efficiency, double tech, long built, String note,
                    String mission, Coord opPoint, int radius) {
    public static final String AIR_DEFENCE = "air_defence";

    /** Before missions. */
    public Plane(long id, int owner, String cls, Coord at, double efficiency, double tech, long built, String note) {
        this(id, owner, cls, at, efficiency, tech, built, note, null, null, 0);
    }

    public Plane withAt(Coord c) { return new Plane(id, owner, cls, c, efficiency, tech, built, note, mission, opPoint, radius); }
    public Plane withEfficiency(double e) { return new Plane(id, owner, cls, at, Math.max(0, Math.min(100, e)), tech, built, note, mission, opPoint, radius); }
    /** A new owner, and none of the old one's orders. */
    public Plane withOwner(int o) { return new Plane(id, o, cls, at, efficiency, tech, built, note, null, null, 0); }
    public Plane withNote(String n) { return new Plane(id, owner, cls, at, efficiency, tech, built, n, mission, opPoint, radius); }
    public Plane withMission(String m, Coord op, int r) { return new Plane(id, owner, cls, at, efficiency, tech, built, note, m, m == null ? null : op, m == null ? 0 : r); }
    public boolean onAirDefence() { return AIR_DEFENCE.equals(mission) && opPoint != null; }
}
