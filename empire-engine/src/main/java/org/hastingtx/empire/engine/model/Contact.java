package org.hastingtx.empire.engine.model;

/**
 * One country's sighting of one enemy ship (issue #75). A contact is what detection leaves behind:
 * it remembers where the target was when it was last seen, not where it is now, so a contact goes
 * stale as the target sails on. {@code confidence} is the probability the sighting was made with,
 * after signature and masking; {@code seenUpdate} is when.
 *
 * <p>Holds the truth of the sighting. What a player is actually told is decided at the view, by
 * confidence band — a faint contact should not read like a firm one.
 */
public record Contact(int owner, long shipId, int targetOwner, String cls, Coord at, long seenUpdate, double confidence) {

    /** Updates since this contact was made, as of {@code now}. */
    public long age(long now) { return now - seenUpdate; }

    /** A contact is stale once it has gone unrefreshed for longer than the configured window. */
    public boolean stale(long now, int stalenessUpdates) { return age(now) > stalenessUpdates; }
}
