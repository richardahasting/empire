package org.hastingtx.empire.engine.model;

/**
 * Cargo stopped mid-route: a partial distribution delivery that ran out of reach or
 * mobility, or a train short of its range. Occupies the sector it stopped in and is
 * captured with it. Re-enters the flow plan at the next update. {@code mode} is "road"
 * (resumes by distribution path) or "rail" (a train: resumes along rail only).
 */
public record HeldParcel(int commodity, double qty, int owner, Coord origin, Coord dest, long issuedUpdate, String mode) {
    /**
     * A parcel holds whole units (issue #77). It is stock like any other — the conservation check sums
     * it alongside sector stock — so a fractional parcel would put a fraction back into a world whose
     * stock is integral. Rounding here covers every creation site, including {@link #withQty}.
     */
    public HeldParcel { qty = Math.floor(qty); }   // floor, to match Ledger.taken: what leaves stock is what the parcel holds

    public HeldParcel(int commodity, double qty, int owner, Coord origin, Coord dest, long issuedUpdate) { this(commodity, qty, owner, origin, dest, issuedUpdate, "road"); }
    public HeldParcel withQty(double q) { return new HeldParcel(commodity, q, owner, origin, dest, issuedUpdate, mode); }
    public boolean rail() { return "rail".equals(mode); }
}
