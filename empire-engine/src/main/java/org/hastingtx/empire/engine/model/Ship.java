package org.hastingtx.empire.engine.model;

import java.util.List;

/**
 * A ship (issue #56). Sits on a sea hex or in a harbour; carries {@code stock} up to its class's hold.
 * {@code dest} is where it is sailing (null = holding); {@code lane} is a standing order between two
 * harbours that overrides dest every update. {@code note} is what it did last update, for the player.
 * {@code tech} is the tech level it was laid at; speed scales with it (KNOWN: the original's ship tech).
 * {@code mission} "fish" (Richard 2026-09-09): wander the grounds near {@code home}, fish, land the catch, repeat.
 */
public record Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home) {
    public static final String FISH = "fish";

    /** Shuttle between two harbours: load {@code cargo} (commodity indices; empty = everything it may carry) above the harbour's thresholds at {@code from}, unload all at {@code to}. */
    public record Lane(Coord from, Coord to, List<Integer> cargo, boolean outbound) {
        public Lane { cargo = List.copyOf(cargo); }
        public Lane turned(boolean out) { return new Lane(from, to, cargo, out); }
        public Coord target() { return outbound ? to : from; }
    }

    public Ship withAt(Coord c) { return new Ship(id, owner, cls, name, c, efficiency, stock, dest, lane, built, note, tech, mission, home); }
    public Ship withEfficiency(double e) { return new Ship(id, owner, cls, name, at, e, stock, dest, lane, built, note, tech, mission, home); }
    public Ship withStock(Stocks s) { return new Ship(id, owner, cls, name, at, efficiency, s, dest, lane, built, note, tech, mission, home); }
    public Ship withDest(Coord d) { return new Ship(id, owner, cls, name, at, efficiency, stock, d, lane, built, note, tech, mission, home); }
    public Ship withLane(Lane l) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, l, built, note, tech, mission, home); }
    public Ship withNote(String n) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, n, tech, mission, home); }
    public Ship withName(String n) { return new Ship(id, owner, cls, n, at, efficiency, stock, dest, lane, built, note, tech, mission, home); }
    public Ship withMission(String m, Coord h) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, m, h); }
    public boolean fishing() { return FISH.equals(mission); }
    public double load() { return stock.total(); }
}
