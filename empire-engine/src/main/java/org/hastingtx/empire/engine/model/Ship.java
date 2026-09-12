package org.hastingtx.empire.engine.model;

import java.util.List;

/**
 * A ship (issue #56). Sits on a sea hex or in a harbour; carries {@code stock} up to its class's hold.
 * {@code dest} is where it is sailing (null = holding); {@code lane} is a standing order between two
 * harbours that overrides dest every update. {@code note} is what it did last update, for the player.
 * {@code tech} is the tech level it was laid at; speed scales with it (KNOWN: the original's ship tech).
 * {@code mission} "fish" (Richard 2026-09-09): wander the grounds near {@code home}, fish, land the catch, repeat.
 * {@code mission} "mine" (issue #112): the same, over the nodule fields, landing ore instead of food.
 * {@code fuel} is what is in the tank (issue #65) — petrol drawn from a harbour or a tanker, burned by
 * the hex. It is not cargo: it is counted in conservation like stock, but a hold full of petrol is
 * freight and a full tank is not. {@code crew} is the people signed on (issue #66) — civilians on a
 * merchantman, military on a warship — counted in conservation the same way, and put ashore when she is
 * scrapped.
 */
public record Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home, double fuel, double crew, double mobility) {

    public Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home, double fuel, double crew) {
        this(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, 0);
    }

    /** Hexes this hull may still travel before it has to wait for the update (issue #69). */
    public Ship withMobility(double m) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, Math.max(0, m)); }

    /** A hull with a dry tank and nobody aboard, for callers that predate fuel and crews (issues #65, #66). */
    public Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home) {
        this(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, 0, 0);
    }

    /** A hull with a tank but no crew — the fuel tests and fixtures that predate #66. */
    public Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home, double fuel) {
        this(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, 0);
    }

    public Ship withFuel(double f) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, Math.max(0, f), crew, mobility); }
    public Ship withCrew(double c) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, Math.max(0, c), mobility); }
    public static final String FISH = "fish";
    /** Issue #112: roam the nodule fields near {@code home}, mine, land the ore, repeat. */
    public static final String MINE = "mine";

    /** A standing mission that roams the sea near home and comes back loaded. */
    public boolean roaming() { return FISH.equals(mission) || MINE.equals(mission); }

    /** Shuttle between two harbours: load {@code cargo} (commodity indices; empty = everything it may carry) above the harbour's thresholds at {@code from}, unload all at {@code to}. */
    public record Lane(Coord from, Coord to, List<Integer> cargo, boolean outbound) {
        public Lane { cargo = List.copyOf(cargo); }
        public Lane turned(boolean out) { return new Lane(from, to, cargo, out); }
        public Coord target() { return outbound ? to : from; }
    }

    public Ship withAt(Coord c) { return new Ship(id, owner, cls, name, c, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility); }
    public Ship withEfficiency(double e) { return new Ship(id, owner, cls, name, at, e, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility); }
    public Ship withStock(Stocks s) { return new Ship(id, owner, cls, name, at, efficiency, s, dest, lane, built, note, tech, mission, home, fuel, crew, mobility); }
    public Ship withDest(Coord d) { return new Ship(id, owner, cls, name, at, efficiency, stock, d, lane, built, note, tech, mission, home, fuel, crew, mobility); }
    public Ship withLane(Lane l) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, l, built, note, tech, mission, home, fuel, crew, mobility); }
    public Ship withNote(String n) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, n, tech, mission, home, fuel, crew, mobility); }
    public Ship withName(String n) { return new Ship(id, owner, cls, n, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility); }
    public Ship withMission(String m, Coord h) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, m, h, fuel, crew, mobility); }
    public boolean fishing() { return FISH.equals(mission); }
    public double load() { return stock.total(); }
}
