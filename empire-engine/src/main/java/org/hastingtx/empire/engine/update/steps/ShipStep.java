package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.SeaRoutes;
import org.hastingtx.empire.engine.update.Step;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 7c (issue #56): ships. In id order — few ships, and a harbour's stock is the only thing two
 * ships can contend for. For each ship: fit out in a harbour, fish, cheer, run its lane, sail.
 */
public final class ShipStep implements Step {
    public String name() { return "ships"; }

    public void run(Ctx ctx) {
        UnitsCfg.ShipsCfg sc = ctx.cfg.units().ships();
        if (sc == null) return;
        List<Ship> out = new ArrayList<>();
        for (Ship ship : ctx.ships) {
            UnitsCfg.ShipClassCfg cls = sc.shipClass(ship.cls());
            StringBuilder note = new StringBuilder();
            Sector here = ctx.snap.sector(ship.at());
            int hi = ctx.idx(ship.at());
            boolean docked = here.owner() == ship.owner() && SeaRoutes.isHarbor(ctx.cfg, here);

            // the sea wears a hull down (Richard 2026-09-11): everything afloat loses efficiency every
            // update, and a ship in its own harbour does not, because it is being looked after there.
            // Efficiency drives speed and the mobility cap, so a tired ship is a slow one before it is
            // anything else.
            if (!docked && sc.seaWearPerUpdate() > 0 && ship.efficiency() > 0) {
                double worn = Math.min(sc.seaWearPerUpdate(), ship.efficiency());
                ship = ship.withEfficiency(ship.efficiency() - worn);
                sep(note).append("worn by the sea, ").append(Ledger.q(ship.efficiency())).append("% left");
            }

            // fit out: a docked hull gains efficiency from the harbour's materials and cash
            if (docked && ship.efficiency() < 100 && here.efficiency() >= sc.harborMinEfficiency()) {
                double points = Math.min(sc.dockPointsPerUpdate(), 100 - ship.efficiency());
                StringBuilder used = new StringBuilder();
                for (var e : sc.dockMaterialsPerPoint().entrySet()) {
                    if (e.getValue() <= 0) continue;
                    if (e.getKey().equals("cash")) points = Math.min(points, Math.max(0, ctx.country(ship.owner()).cash() + ctx.led().cash[ship.owner()]) / e.getValue());
                    else { int c = ctx.com.index(e.getKey()); double avail = here.stock().get(c) + ctx.led().st(hi, c); points = Math.min(points, avail / e.getValue()); if (avail < sc.dockPointsPerUpdate() * e.getValue()) ctx.led().shortOf(hi, c, sc.dockPointsPerUpdate() * e.getValue() - avail); }
                }
                if (points > 1e-9) {
                    for (var e : sc.dockMaterialsPerPoint().entrySet()) {
                        if (e.getValue() <= 0) continue;
                        if (e.getKey().equals("cash")) { ctx.led().cash[ship.owner()] -= points * e.getValue(); used.append(used.isEmpty() ? "" : ", ").append('$').append(Ledger.q(points * e.getValue())); }
                        else { ctx.led().consume(hi, ctx.com.index(e.getKey()), points * e.getValue()); used.append(used.isEmpty() ? "" : ", ").append(Ledger.q(points * e.getValue())).append(' ').append(e.getKey()); }
                    }
                    ship = ship.withEfficiency(ship.efficiency() + points);
                    note.append("fitted out to ").append(Ledger.q(ship.efficiency())).append('%').append(used.isEmpty() ? "" : " using " + used);
                    ctx.led().note(hi, label(ship) + " fitted out to " + Ledger.q(ship.efficiency()) + "%" + (used.isEmpty() ? "" : " using " + used));
                }
            }
            double eff = ship.efficiency() / 100.0;
            // the pool fills by the hull's speed and is capped, so idling banks a dash but not a
            // teleport (issue #69). Everything below spends from it, whether ordered now or run here.
            ship = ship.withMobility(Math.min(sc.mobilityCap(cls, ship.tech(), ship.efficiency()),
                    ship.mobility() + sc.range(cls, ship.tech(), ship.efficiency())));

            // fishing: food from the sea hex's fertility into the hold
            if (cls.fishingRateOr0() > 0 && here.terrain() == Terrain.OCEAN && eff > 0) {
                double room = Math.max(0, cls.hold() - ship.load());
                double fish = Math.min(room, cls.fishingRateOr0() * here.fertility() * ctx.etus * sc.fishingFoodPerEtuPerFertilityPoint() * eff);
                fish = ctx.led().produceAtSea(ctx.com.food, fish);   // whole units, tallied where it is made (issue #77)
                if (fish > 0) { ship = ship.withStock(ship.stock().plus(ctx.com.food, fish)); sep(note).append("fished ").append(Ledger.q(fish)).append(" food"); if (room - fish < 1e-9) note.append(" (hold full)"); }
                else if (room <= 1e-9) sep(note).append("hold full, no fishing");
            }
            // seabed mining (issue #112): iron from the hex's nodules into the hold. The same shape as
            // fishing because it is the same mission — roam, work the water you are over, come home.
            if (cls.miningRateOr0() > 0 && here.terrain() == Terrain.OCEAN && eff > 0) {
                double room = Math.max(0, cls.hold() - ship.load());
                double ore = Math.min(room, cls.miningRateOr0() * here.resource("minerals") * ctx.etus * sc.miningOrePerEtuPerMineralPoint() * eff);
                ore = ctx.led().produceAtSea(ctx.com.index("iron"), ore);
                if (ore > 0) { ship = ship.withStock(ship.stock().plus(ctx.com.index("iron"), ore)); sep(note).append("mined ").append(Ledger.q(ore)).append(" iron"); if (room - ore < 1e-9) note.append(" (hold full)"); }
                else if (room <= 1e-9) sep(note).append("hold full, no mining");
                else if (here.resource("minerals") <= 0) sep(note).append("no nodules here");
            }
            // luxury: happiness while at sea
            if (cls.happinessOr0() > 0 && here.terrain() == Terrain.OCEAN && eff > 0) {
                double h = cls.happinessOr0() * ctx.etus * eff;
                ctx.led().level[ship.owner()][3] += h;
                sep(note).append("cruised: +").append(Ledger.q(h)).append(" happiness");
            }
            // lane: load at from, unload at to, and always know where to go next
            if (ship.lane() != null) {
                Ship.Lane lane = ship.lane();
                if (!lane.outbound() && ship.at().equals(lane.from())) {
                    ship = load(ctx, ship, cls, here, hi, lane.cargo(), note);
                    ship = ship.withLane(lane.turned(true));
                } else if (lane.outbound() && ship.at().equals(lane.to())) {
                    ship = unload(ctx, ship, here, hi, note);
                    ship = ship.withLane(lane.turned(false));
                }
                ship = ship.withDest(ship.lane().target());
            } else if (ship.roaming() && ship.home() != null) {
                // one mission, two things to look for (issue #112): land the load at home, then roam the
                // water, turning for home when the hold fills. Only the weighting differs.
                boolean mining = org.hastingtx.empire.engine.model.Ship.MINE.equals(ship.mission());
                var fc = mining ? sc.miningOrDefault() : sc.fishingOrDefault();
                // too worn to be out here: break off and make for home. The mission is kept, so the
                // ship goes back to work by itself — but not until it is fully refitted, so a worn
                // fleet is a real cost and not a rounding error.
                boolean refitting = ship.efficiency() <= sc.refitBelow()
                        || (docked && ship.efficiency() < sc.refitResumeAt());
                if (refitting) {
                    if (docked) {
                        if (ship.load() > 0) ship = unload(ctx, ship, here, hi, note);
                        sep(note).append("refitting; it will not go out again until it is at 100%");
                        ship = ship.withDest(null);
                    } else {
                        sep(note).append("too worn to work, making for ").append(ship.home());
                        ship = ship.withDest(ship.home());
                    }
                } else {
                if (ship.at().equals(ship.home()) && ship.load() > 0) ship = unload(ctx, ship, here, hi, note);
                boolean full = ship.load() >= cls.hold() * fc.returnWhenHoldFraction() - 1e-9;
                if (full) { if (!ship.home().equals(ship.dest())) sep(note).append("hold ").append(Ledger.q(100 * ship.load() / cls.hold())).append("% full, heading home to ").append(ship.home()); ship = ship.withDest(ship.home()); }
                else if (ship.dest() == null || ship.at().equals(ship.dest()) || ship.dest().equals(ship.home())) {
                    Coord next = pickWaters(ctx, ship, fc, mining);
                    if (next == null) { sep(note).append(mining ? "no nodule fields within " : "no fishing grounds within ").append(fc.radius()).append(" of ").append(ship.home()); ship = ship.withDest(null); }
                    else ship = ship.withDest(next);
                }
                }
            } else if (docked && sc.autoUnloadInHarbor() && cls.worksTheSea() && ship.load() > 0) {
                ship = unload(ctx, ship, here, hi, note);   // home from the water, the load goes ashore
            }
            // refuel (issue #65): a harbour pumps from its own stock, a tanker from its hold at sea
            if (sc.fuel() && docked) ship = refuel(ctx, ship, cls, hi, note);
            else if (sc.fuel()) ship = refuelAtSea(ctx, ship, cls, out, note);
            // sign on a crew (issue #66): only a harbour can, and only from the people who are there
            if (sc.crews() && docked) ship = muster(ctx, ship, cls, hi, note);
            // sail
            if (ship.dest() != null && !ship.dest().equals(ship.at())) {
                List<Coord> path = SeaRoutes.path(ctx.snap, ctx.cfg, ship.owner(), ship.at(), ship.dest());
                if (path == null) sep(note).append("no sea route to ").append(ship.dest());
                else {
                    int range = (int) Math.floor(ship.mobility());
                    int hops = Math.min(range, path.size() - 1);
                    // short-handed is not going anywhere (issue #66)
                    if (sc.crews() && ship.crew() < cls.crewOr0()) {
                        sep(note).append("short-handed: ").append(Ledger.q(ship.crew())).append(" of ").append(Ledger.q(cls.crewOr0()))
                                 .append(' ').append(ctx.com.id(crewCommodity(ctx, cls))).append(" aboard");
                        hops = 0;
                    }
                    // a dry tank holds the ship where it is (issue #65)
                    double perHex = sc.fuel() ? cls.fuelPerHexOr0() : 0;
                    int fuelled = perHex > 0 ? (int) Math.floor(ship.fuel() / perHex) : hops;
                    if (perHex > 0 && fuelled < hops) hops = Math.max(0, fuelled);
                    if (hops <= 0 && sc.crews() && ship.crew() < cls.crewOr0()) { /* already said so */ }
                    else if (hops <= 0 && perHex > 0 && ship.fuel() < perHex) sep(note).append("out of fuel, holding at ").append(ship.at());
                    else if (hops <= 0) sep(note).append("too unfit to sail (").append(Ledger.q(ship.efficiency())).append("%)");
                    else {
                        Coord to = path.get(hops);
                        ship = ship.withAt(to).withMobility(ship.mobility() - hops);
                        if (perHex > 0) {
                            double burned = hops * perHex;
                            ship = ship.withFuel(ship.fuel() - burned);
                            ctx.led().destroyed(ctx.com.index(sc.fuelId()), burned);   // burned fuel leaves the world
                        }
                        sep(note).append("sailed ").append(hops).append(hops == 1 ? " hex" : " hexes").append(" to ").append(to);
                        if (perHex > 0 && ship.fuel() < perHex) note.append(" (tank dry)");
                        if (to.equals(ship.dest())) { note.append(", arrived"); if (ship.lane() == null) ship = ship.withDest(null); }
                    }
                }
            } else if (ship.dest() != null) { if (ship.lane() == null) ship = ship.withDest(null); }
            // upkeep
            if (cls.upkeepPerUpdate() != null) for (var e : cls.upkeepPerUpdate().entrySet()) if (e.getKey().equals("cash")) ctx.led().cash[ship.owner()] -= e.getValue();
            out.add(ship.withNote(note.isEmpty() ? (docked ? "in harbour" : "holding") : note.toString()));
        }
        ctx.ships.clear(); ctx.ships.addAll(out);
    }

    /**
     * Top the tank up from the harbour's own stock (issue #65). The fuel is not consumed here — it moves
     * from a sector into a tank, and a tank is counted in conservation exactly like a hold, because fuel
     * sitting in a ship is still fuel. It leaves the world when it is burned, a hex at a time.
     */
    private static Ship refuel(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, int hi, StringBuilder note) {
        double room = cls.tankOr0() - ship.fuel();
        if (room < 1) return ship;
        int pet = ctx.com.index(ctx.cfg.units().ships().fuelId());
        double have = ctx.sector(hi).stock().get(pet) + ctx.led().st(hi, pet);
        double took = ctx.led().toShip(hi, pet, Math.min(room, Math.max(0, have)));
        if (took <= 0) { if (ship.fuel() < cls.fuelPerHexOr0()) sep(note).append("no ").append(ctx.com.id(pet)).append(" in the harbour to refuel"); return ship; }
        sep(note).append("took on ").append(Ledger.q(took)).append(' ').append(ctx.com.id(pet));
        return ship.withFuel(ship.fuel() + took);
    }

    /**
     * A tanker in the same hex pumps from its hold into this ship's tank (issue #65) — that is what a
     * tanker is for, and without it a fleet's reach is a harbour's reach. Both hold and tank are counted
     * in conservation, so this is a plain move with nothing to tally.
     *
     * <p>Only tankers already processed this update can give: the list is walked in id order and a ship
     * that has not moved yet is not in {@code done}. That keeps it deterministic — who fuels whom cannot
     * depend on anything but the order the ships were built.
     */
    private static Ship refuelAtSea(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, List<Ship> done, StringBuilder note) {
        double room = cls.tankOr0() - ship.fuel();
        if (room < 1 || ship.fuel() >= cls.fuelPerHexOr0()) return ship;   // only a ship that needs it
        int pet = ctx.com.index(ctx.cfg.units().ships().fuelId());
        for (int k = 0; k < done.size(); k++) {
            Ship t = done.get(k);
            if (t.owner() != ship.owner() || !t.at().equals(ship.at())) continue;
            if (!"tanker".equals(ctx.cfg.units().ships().shipClass(t.cls()).role())) continue;
            double give = Math.floor(Math.min(room, t.stock().get(pet)));
            if (give < 1) continue;
            done.set(k, t.withStock(t.stock().plus(pet, -give)));
            sep(note).append("refuelled ").append(Ledger.q(give)).append(' ').append(ctx.com.id(pet)).append(" from ").append(label(t));
            return ship.withFuel(ship.fuel() + give);
        }
        return ship;
    }

    /** Civilians on a merchantman, military on a warship (issue #66). */
    static int crewCommodity(Ctx ctx, UnitsCfg.ShipClassCfg cls) {
        return ctx.cfg.units().ships().crewIsCivilian(cls) ? ctx.com.civ : ctx.com.mil;
    }

    /**
     * Sign on whoever is missing, from the people standing in the harbour (issue #66). A crew is not
     * cargo — it does not eat into the hold and it does not get unloaded with the catch — but it is
     * people, counted in conservation like any other, and it goes ashore when the hull is scrapped.
     *
     * <p>They come out of the harbour's own population, so a fleet competes with a factory for the same
     * civilians. That is the cost the feature exists to impose.
     */
    private static Ship muster(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, int hi, StringBuilder note) {
        double want = cls.crewOr0() - ship.crew();
        if (want < 1) return ship;
        int who = crewCommodity(ctx, cls);
        double have = ctx.sector(hi).stock().get(who) + ctx.led().st(hi, who);
        double got = ctx.led().toShip(hi, who, Math.min(want, Math.max(0, have)));
        if (got <= 0) { sep(note).append("no ").append(ctx.com.id(who)).append(" in the harbour to crew her"); return ship; }
        sep(note).append("signed on ").append(Ledger.q(got)).append(' ').append(ctx.com.id(who));
        return ship.withCrew(ship.crew() + got);
    }

    private static StringBuilder sep(StringBuilder sb) { if (!sb.isEmpty()) sb.append("; "); return sb; }

    /**
     * The next cast: a sea hex within {@code wander_hops} of the boat (or, from home, anywhere in the
     * grounds) and within {@code radius} of home, drawn with probability ∝ fertility + 1 from a seeded
     * stream per ship and update — a random path that still favours rich water.
     */
    static Coord pickWaters(Ctx ctx, Ship ship, UnitsCfg.ShipsCfg.FishingCfg fc, boolean mining) {
        java.util.SplittableRandom rng = org.hastingtx.empire.engine.update.Rng.stream((mining ? "mining:" : "fishing:") + ship.id() + ":" + ctx.snap.updateNumber(), ctx.seed);
        boolean atHome = ship.at().equals(ship.home());
        int hops = atHome ? fc.radius() : fc.wanderHops();
        List<Coord> cands = new ArrayList<>(); List<Double> weights = new ArrayList<>(); double total = 0;
        for (Sector s : ctx.snap.sectors()) {
            if (s.terrain() != Terrain.OCEAN || s.at().equals(ship.at())) continue;
            if (org.hastingtx.empire.engine.geo.Hex.distance(ctx.snap, s.at(), ship.home()) > fc.radius()) continue;
            if (org.hastingtx.empire.engine.geo.Hex.distance(ctx.snap, s.at(), ship.at()) > hops) continue;
            if (SeaRoutes.path(ctx.snap, ctx.cfg, ship.owner(), ship.at(), s.at()) == null) continue;
            // richer water more often; a mission looks for what it is there for
            double wgt = (mining ? s.resource("minerals") : s.fertility()) + 1.0;
            cands.add(s.at()); weights.add(wgt); total += wgt;
        }
        if (cands.isEmpty()) return null;
        double r = rng.nextDouble() * total;
        for (int i = 0; i < cands.size(); i++) { r -= weights.get(i); if (r <= 0) return cands.get(i); }
        return cands.get(cands.size() - 1);
    }
    static String label(Ship s) { return "ship #" + s.id() + (s.name() == null || s.name().isBlank() ? "" : " " + s.name()); }

    /**
     * The sectors a docked ship may work, in a fixed order: its harbour first, then any {@code dockside}
     * sector of the same country in an adjacent hex — the warehouse next door (issue #78). Neighbour
     * order is deterministic, so two ships working the same warehouse contend exactly as they already do
     * over harbour stock, resolved through the ledger in ship-id order.
     */
    static List<Integer> dockside(Ctx ctx, Sector harbor, int owner) {
        List<Integer> out = new ArrayList<>();
        out.add(ctx.idx(harbor.at()));
        for (Coord nb : org.hastingtx.empire.engine.geo.Hex.neighbours(ctx.snap, harbor.at())) {
            int i = ctx.idx(nb);
            Sector s = ctx.sector(i);
            if (s.owner() == owner && ctx.type(s).hasFlag("dockside")) out.add(i);
        }
        return out;
    }

    /** What a class may carry. */
    public static boolean carries(Ctx ctx, UnitsCfg.ShipClassCfg cls, int c) {
        for (String k : cls.carriesOrEmpty()) {
            if (k.equals("all")) return true;
            if (k.equals("goods") && !ctx.com.isPerson(c)) return true;
            if (k.equals("people") && ctx.com.isPerson(c)) return true;
            if (ctx.com.has(k) && ctx.com.index(k) == c) return true;
        }
        return false;
    }

    /** Load surplus (above each sector's thresholds) from the harbour, then any dockside warehouse, up to the hold. */
    private static Ship load(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, Sector harbor, int hi, List<Integer> wanted, StringBuilder note) {
        double room = cls.hold() - ship.load();
        StringBuilder took = new StringBuilder();
        for (int si : dockside(ctx, harbor, ship.owner())) {
            if (room <= 1e-9) break;
            Sector src = ctx.sector(si);
            StringBuilder here = new StringBuilder();
            for (int c = 0; c < ctx.com.size() && room > 1e-9; c++) {
                if (!wanted.isEmpty() && !wanted.contains(c)) continue;
                if (!carries(ctx, cls, c)) continue;
                double keep = src.hasThreshold(c) ? src.threshold(c) : 0;
                double avail = src.stock().get(c) + ctx.led().st(si, c) - keep;
                double q = Math.min(room, avail);
                if (q <= 1e-9) continue;
                q = ctx.led().toShip(si, c, q);              // whole units both sides, or the two disagree (issue #77)
                if (q <= 0) continue;
                room -= q;
                ship = ship.withStock(ship.stock().plus(c, q));
                here.append(here.isEmpty() ? "" : ", ").append(Ledger.q(q)).append(' ').append(ctx.com.id(c));
            }
            if (here.isEmpty()) continue;
            took.append(took.isEmpty() ? "" : "; ").append(here).append(si == hi ? "" : " from the warehouse at " + src.at());
            ctx.led().note(si, label(ship) + " loaded " + here);
        }
        if (!took.isEmpty()) sep(note).append("loaded ").append(took).append(" at ").append(harbor.at());
        else sep(note).append("nothing to load at ").append(harbor.at());
        return ship;
    }

    /** Unload into the harbour, then into any dockside warehouse that still has room (issue #78). */
    private static Ship unload(Ctx ctx, Ship ship, Sector harbor, int hi, StringBuilder note) {
        StringBuilder put = new StringBuilder();
        Stocks st = ship.stock();
        for (int si : dockside(ctx, harbor, ship.owner())) {
            Sector dst = ctx.sector(si);
            StringBuilder here = new StringBuilder();
            for (int c = 0; c < ctx.com.size(); c++) {
                double q = st.get(c);
                if (q <= 1e-9) continue;
                double room = ctx.com.isPerson(c)
                        ? Math.max(0, ctx.maxPopulation(dst) - (dst.stock().get(ctx.com.civ) + ctx.led().st(si, ctx.com.civ) + dst.stock().get(ctx.com.uw) + ctx.led().st(si, ctx.com.uw)))
                        : Math.max(0, ctx.capacity(dst, c) - (dst.stock().get(c) + ctx.led().st(si, c)));
                double u = Math.min(q, room);
                if (u <= 1e-9) continue;
                u = ctx.led().fromShip(si, c, u);
                if (u <= 0) continue;
                st = st.plus(c, -u);
                here.append(here.isEmpty() ? "" : ", ").append(Ledger.q(u)).append(' ').append(ctx.com.id(c));
            }
            if (here.isEmpty()) continue;
            put.append(put.isEmpty() ? "" : "; ").append(here).append(si == hi ? "" : " into the warehouse at " + dst.at());
            ctx.led().note(si, label(ship) + " unloaded " + here);
        }
        if (!put.isEmpty()) sep(note).append("unloaded ").append(put).append(" at ").append(harbor.at());
        return ship.withStock(st);
    }
}
