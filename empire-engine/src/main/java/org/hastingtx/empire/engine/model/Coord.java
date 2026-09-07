package org.hastingtx.empire.engine.model;

/** Offset (odd-r) coordinates. Immutable. */
public record Coord(int x, int y) implements Comparable<Coord> {
    @Override public int compareTo(Coord o) { return y != o.y ? Integer.compare(y, o.y) : Integer.compare(x, o.x); }
    @Override public String toString() { return x + "," + y; }
}
