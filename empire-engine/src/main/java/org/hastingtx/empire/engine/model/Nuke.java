package org.hastingtx.empire.engine.model;

/**
 * A nuclear warhead (issue #71; KNOWN nuke.c, struct nukstr): one of a class in {@code units.nukes}, built whole in a
 * nuclear plant. {@code plane} is the plane it is armed on (KNOWN nuk_plane), 0 for none; armed, it is wherever that
 * plane is and whoever's that plane is — {@link World#nukeAt} and {@link World#nukeOwner} — and goes when the plane goes.
 * {@code airburst} is how it is set to go off (KNOWN PLN_AIRBURST, set when armed).
 */
public record Nuke(long id, int owner, String cls, Coord at, double tech, long built, long plane, boolean airburst) {
    public boolean armed() { return plane != 0; }
    /** Armed on {@code p}, set to go off as {@code air}. */
    public Nuke armedOn(Plane p, boolean air) { return new Nuke(id, p.owner(), cls, p.at(), tech, built, p.id(), air); }
    /** Taken off its plane, where it was and whose it was. */
    public Nuke disarmed(int o, Coord where) { return new Nuke(id, o, cls, where, tech, built, 0, false); }
    public Nuke withOwner(int o) { return new Nuke(id, o, cls, at, tech, built, plane, airburst); }
}
