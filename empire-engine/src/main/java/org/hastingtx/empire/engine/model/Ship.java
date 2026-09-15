package org.hastingtx.empire.engine.model;

import java.util.List;

/**
 * A ship (issue #56). Sits on a sea hex or in a harbour; carries {@code stock} up to its class's hold.
 * {@code dest} is where it is sailing (null = holding); {@code lane} is a standing order between two
 * harbours that overrides dest every update. {@code note} is what it did last update, for the player.
 * {@code tech} is the tech level it was laid at; speed scales with it (KNOWN: the original's ship tech).
 * {@code mission} "fish" (Richard 2026-09-09): wander the grounds near {@code home}, fish, land the catch, repeat.
 * {@code mission} "mine" (issue #112): the same, over the nodule fields, landing ore instead of food.
 * {@code mission} "supply" (issue #67): carry between your own harbours whatever their thresholds are short of.
 * {@code fuel} is what is in the tank (issue #65) — petrol drawn from a harbour or a tanker, burned by
 * the hex. It is not cargo: it is counted in conservation like stock, but a hold full of petrol is
 * freight and a full tank is not. {@code crew} is the people signed on (issue #66) — civilians on a
 * merchantman, military on a warship — counted in conservation the same way, and put ashore when she is
 * scrapped.
 */
public record Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home, double fuel, double crew, double mobility, java.util.Map<Integer, Long> firedOn, List<Coord> route, long ward, boolean handLeg,
                   java.util.Map<String, Double> manifest) {

    public Ship {
        firedOn = firedOn == null || firedOn.isEmpty() ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(firedOn));
        route = route == null ? List.of() : List.copyOf(route);
        manifest = manifest == null || manifest.isEmpty() ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(manifest));
    }

    /** Before the manifest (issue #244): nothing on the books. */
    public Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home, double fuel, double crew, double mobility, java.util.Map<Integer, Long> firedOn, List<Coord> route, long ward, boolean handLeg) {
        this(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, handLeg, java.util.Map.of());
    }

    /*
     * The running manifest (issue #244, Richard 2026-09-15: "how much food, iron, happiness, or deliveries each ship has
     * created since the ship's creation"). Lifetime totals by key, in canonical order: "caught food", "mined iron",
     * "happiness", "delivered <commodity>", "refuelled ships" and "fuel given" for a tender.
     */
    public static final String CAUGHT = "caught ", MINED = "mined ", HAPPINESS = "happiness", DELIVERED = "delivered ", RESCUES = "ships helped", FUEL_GIVEN = "fuel given";
    /** Add {@code qty} to a lifetime total. */
    public Ship tally(String key, double qty) {
        if (!(qty > 0)) return this;
        java.util.Map<String, Double> m = new java.util.TreeMap<>(manifest);
        m.merge(key, qty, Double::sum);
        return withManifest(m);
    }
    public Ship withManifest(java.util.Map<String, Double> m) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, handLeg, m); }

    /** Before hand legs (issues #201, #205): never on one. */
    public Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home, double fuel, double crew, double mobility, java.util.Map<Integer, Long> firedOn, List<Coord> route, long ward) {
        this(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, false);
    }

    public Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home, double fuel, double crew, double mobility, java.util.Map<Integer, Long> firedOn) {
        this(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, firedOn, List.of(), 0);
    }

    public Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home, double fuel, double crew, double mobility) {
        this(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, java.util.Map.of());
    }

    /**
     * Issue #68: this hull fired on a country it was at peace with, and that country may shoot it on
     * sight until the update given. The mark is on the ship, not the flag — the rest of her navy is
     * not fair game — and it sinks with her.
     */
    public Ship withFiredOn(java.util.Map<Integer, Long> f) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, f, route, ward, handLeg, manifest); }
    /** Whether {@code country} may engage this hull without a war, as of update {@code now}. */
    public boolean firedOnBy(int country, long now) { Long until = firedOn.get(country); return until != null && until >= now; }

    public Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home, double fuel, double crew) {
        this(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, 0);
    }

    /** Hexes this hull may still travel before it has to wait for the update (issue #69). */
    public Ship withMobility(double m) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, Math.max(0, m), firedOn, route, ward, handLeg, manifest); }

    /** A hull with a dry tank and nobody aboard, for callers that predate fuel and crews (issues #65, #66). */
    public Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home) {
        this(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, 0, 0);
    }

    /** A hull with a tank but no crew — the fuel tests and fixtures that predate #66. */
    public Ship(long id, int owner, String cls, String name, Coord at, double efficiency, Stocks stock, Coord dest, Lane lane, long built, String note, double tech, String mission, Coord home, double fuel) {
        this(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, 0);
    }

    public Ship withFuel(double f) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, Math.max(0, f), crew, mobility, firedOn, route, ward, handLeg, manifest); }
    public Ship withCrew(double c) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, Math.max(0, c), mobility, firedOn, route, ward, handLeg, manifest); }
    public static final String FISH = "fish";
    /** Issue #112: roam the nodule fields near {@code home}, mine, land the ore, repeat. */
    public static final String MINE = "mine";

    /**
     * Issue #67: keep your harbours' thresholds filled from whichever harbour can spare it, without a
     * shipment ever being ordered. {@code home} is where she goes to be refitted.
     */
    public static final String SUPPLY = "supply";

    /** A standing mission that roams the sea near home and comes back loaded. */
    public boolean roaming() { return FISH.equals(mission) || MINE.equals(mission); }
    public boolean supplying() { return SUPPLY.equals(mission); }

    /**
     * Issues #201, #205 (Richard 2026-09-14, "pause and resume"): a {@code sail} given to a ship with a standing
     * order sends her there by hand and keeps the order, which steers her again once she arrives. While
     * on a hand leg the order's own steering is skipped; the automatic rules (fuel, limping) still apply.
     * Only {@code off} ends a standing order.
     */
    public Ship withHandLeg(boolean h) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, h, manifest); }
    /** The standing order she has, lane included, as a word for the player: fishing, mining, supply, patrol… */
    public String orderLabel() {
        if (lane != null) return "lane";
        if (mission == null || mission.isBlank()) return null;
        return switch (mission) { case FISH -> "fishing"; case MINE -> "mining"; default -> mission; };
    }

    /*
     * Military missions (issue #68). Where a warship goes; whether she shoots is the war's business, not
     * the mission's. {@code route} holds a patrol's waypoints, or the one station a blockade or an
     * interdiction holds; {@code ward} is the ship an escort stays with. Every one of them comes home
     * to {@code home} for shells, fuel, crew and repairs, and goes back out by itself.
     */
    public static final String PATROL = "patrol", SEARCH = "search", ESCORT = "escort", BLOCKADE = "blockade", INTERDICT = "interdict";
    public static final java.util.Set<String> MILITARY_MISSIONS = java.util.Set.of(PATROL, SEARCH, ESCORT, BLOCKADE, INTERDICT);
    public boolean onMilitaryMission() { return mission != null && MILITARY_MISSIONS.contains(mission); }
    /** Issue #182: a tender on her way to the ship in distress in {@code ward}, or to one she has helped. */
    public static final String RESCUE = "rescue";
    public boolean rescuing() { return RESCUE.equals(mission); }
    /** The station a blockade or interdiction holds, or null. */
    public Coord station() { return (BLOCKADE.equals(mission) || INTERDICT.equals(mission)) && !route.isEmpty() ? route.get(0) : null; }
    public Ship withOrders(String m, Coord h, List<Coord> r, long w) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, m, h, fuel, crew, mobility, firedOn, r, w, handLeg, manifest); }

    /** Shuttle between two harbours: load {@code cargo} (commodity indices; empty = everything it may carry) above the harbour's thresholds at {@code from}, unload all at {@code to}. */
    public record Lane(Coord from, Coord to, List<Integer> cargo, boolean outbound) {
        public Lane { cargo = List.copyOf(cargo); }
        public Lane turned(boolean out) { return new Lane(from, to, cargo, out); }
        public Coord target() { return outbound ? to : from; }
    }

    public Ship withAt(Coord c) { return new Ship(id, owner, cls, name, c, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, handLeg, manifest); }
    public Ship withEfficiency(double e) { return new Ship(id, owner, cls, name, at, e, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, handLeg, manifest); }
    public Ship withStock(Stocks s) { return new Ship(id, owner, cls, name, at, efficiency, s, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, handLeg, manifest); }
    public Ship withDest(Coord d) { return new Ship(id, owner, cls, name, at, efficiency, stock, d, lane, built, note, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, handLeg, manifest); }
    public Ship withLane(Lane l) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, l, built, note, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, handLeg, manifest); }
    public Ship withNote(String n) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, n, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, handLeg, manifest); }
    public Ship withName(String n) { return new Ship(id, owner, cls, n, at, efficiency, stock, dest, lane, built, note, tech, mission, home, fuel, crew, mobility, firedOn, route, ward, handLeg, manifest); }
    /** A new mission, or none: any patrol route or escort charge from the last one is forgotten. */
    public Ship withMission(String m, Coord h) { return new Ship(id, owner, cls, name, at, efficiency, stock, dest, lane, built, note, tech, m, h, fuel, crew, mobility, firedOn, List.of(), 0, false, manifest); }
    public boolean fishing() { return FISH.equals(mission); }
    public double load() { return stock.total(); }
}
