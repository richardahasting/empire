package org.hastingtx.empire.engine.model;

/**
 * A plane (issue #262, #71 slice 3a): one aircraft of a class in {@code units.planes}, sitting on an airfield of yours.
 * {@code tech} is the level it was built at (KNOWN pln_tech); {@code efficiency} is its condition, and below the
 * class minimum it is wreckage. A plane carries nothing between sorties: it takes its petrol and its bombs from the
 * field each time it flies, as the original's {@code pln_equip} does.
 *
 * <p>{@code note} is what it did last, for the reply and the panel. {@code mission} is a standing order (issue #71;
 * KNOWN miss.c): {@link #AIR_DEFENCE} guards the sectors within {@code radius} of {@code opPoint}, or null for none.
 * {@code ship} is the carrier it is aboard (issue #71; KNOWN pln_ship), 0 for none: it flies from her, draws on her hold,
 * and goes where she goes.
 */
public record Plane(long id, int owner, String cls, Coord at, double efficiency, double tech, long built, String note,
                    String mission, Coord opPoint, int radius, long ship) {
    public static final String AIR_DEFENCE = "air_defence";

    /** Before missions. */
    public Plane(long id, int owner, String cls, Coord at, double efficiency, double tech, long built, String note) {
        this(id, owner, cls, at, efficiency, tech, built, note, null, null, 0, 0);
    }

    /** Before carriers. */
    public Plane(long id, int owner, String cls, Coord at, double efficiency, double tech, long built, String note, String mission, Coord opPoint, int radius) {
        this(id, owner, cls, at, efficiency, tech, built, note, mission, opPoint, radius, 0);
    }

    public Plane withAt(Coord c) { return new Plane(id, owner, cls, c, efficiency, tech, built, note, mission, opPoint, radius, ship); }
    public Plane withEfficiency(double e) { return new Plane(id, owner, cls, at, Math.max(0, Math.min(100, e)), tech, built, note, mission, opPoint, radius, ship); }
    /** A new owner, and none of the old one's orders. */
    public Plane withOwner(int o) { return new Plane(id, o, cls, at, efficiency, tech, built, note, null, null, 0, ship); }
    public Plane withNote(String n) { return new Plane(id, owner, cls, at, efficiency, tech, built, n, mission, opPoint, radius, ship); }
    public Plane withMission(String m, Coord op, int r) { return new Plane(id, owner, cls, at, efficiency, tech, built, note, m, m == null ? null : op, m == null ? 0 : r, ship); }
    /** Aboard carrier {@code s} (0: ashore), and where she is. */
    public Plane withShip(long s, Coord where) { return new Plane(id, owner, cls, where, efficiency, tech, built, note, mission, opPoint, radius, s); }
    public boolean aboard() { return ship != 0; }
    public boolean onAirDefence() { return AIR_DEFENCE.equals(mission) && opPoint != null; }
}
