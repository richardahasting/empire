package org.hastingtx.empire.engine.model;

/**
 * Cargo stopped mid-route: a partial distribution delivery that ran out of reach or
 * mobility, or a train short of its range. Occupies the sector it stopped in and is
 * captured with it. Re-enters the flow plan at the next update. {@code mode} is "road"
 * (resumes by distribution path) or "rail" (a train: resumes along rail only).
 */
public record HeldParcel(int commodity, double qty, int owner, Coord origin, Coord dest, long issuedUpdate, String mode) {
    public HeldParcel(int commodity, double qty, int owner, Coord origin, Coord dest, long issuedUpdate) { this(commodity, qty, owner, origin, dest, issuedUpdate, "road"); }
    public HeldParcel withQty(double q) { return new HeldParcel(commodity, q, owner, origin, dest, issuedUpdate, mode); }
    public boolean rail() { return "rail".equals(mode); }
}
