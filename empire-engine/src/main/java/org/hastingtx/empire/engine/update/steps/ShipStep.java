package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.SeaRoutes;
import org.hastingtx.empire.engine.update.Step;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Step 7c (issue #56): ships. In id order — few ships, and a harbour's stock is the only thing two
 * ships can contend for. For each ship: fit out in a harbour, fish, cheer, run its lane or its supply
 * round, sail — and, having arrived, do the harbour's business in the same update (issue #67).
 */
public final class ShipStep implements Step {
    public String name() { return "ships"; }

    public void run(Ctx ctx) {
        UnitsCfg.ShipsCfg sc = ctx.cfg.units().ships();
        if (sc == null) return;
        List<Ship> out = new ArrayList<>();
        Map<Integer, List<Sector>> harbours = new HashMap<>();   // per owner, found once an update
        Map<Integer, int[]> harbourDistances = new HashMap<>();   // per owner: hexes from every sector to its nearest harbour
        // lines for ships not yet processed, written by a tender that reached them first (issue #182)
        Map<Long, List<String>> told = new HashMap<>();
        dispatchTenders(ctx, sc, harbours, told);
        for (int si = 0; si < ctx.ships.size(); si++) {
            Ship ship = ctx.ships.get(si);
            UnitsCfg.ShipClassCfg cls = sc.shipClass(ship.cls());
            Log note = new Log();
            for (String line : told.getOrDefault(ship.id(), List.of())) note.next().append(line);
            Sector here = ctx.snap.sector(ship.at());
            int hi = ctx.idx(ship.at());
            boolean docked = here.owner() == ship.owner() && SeaRoutes.isHarbor(ctx.cfg, here);

            // the sea wears a hull down (Richard 2026-09-11): everything afloat loses efficiency every
            // update, and a ship in its own harbour does not, because it is being looked after there.
            // Efficiency drives speed and the mobility cap, so a tired ship is a slow one before it is
            // anything else.
            // It stops at the least a hull needs to make one hex an update (Richard 2026-09-13, "limp
            // home"), so a ship left at sea can always crawl back to be refitted.
            double floor = sc.limpFloor(cls, ship.tech());
            if (!docked && sc.seaWear() > 0 && ship.efficiency() > floor) {
                double worn = Math.min(sc.seaWear(), ship.efficiency() - floor);
                ship = ship.withEfficiency(ship.efficiency() - worn);
                note.next().append("worn by the sea, ").append(Ledger.q(ship.efficiency())).append("% left");
                if (ship.efficiency() <= floor + 1e-9) note.last().append(" — as worn as the sea will make her; she can still limp home");
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
                    note.next().append("fitted out to ").append(Ledger.q(ship.efficiency())).append('%').append(used.isEmpty() ? "" : " using " + used);
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
                if (fish > 0) { ship = ship.withStock(ship.stock().plus(ctx.com.food, fish)).tally(Ship.CAUGHT + ctx.com.id(ctx.com.food), fish); note.next().append("fished ").append(Ledger.q(fish)).append(" food"); if (room - fish < 1e-9) note.last().append(" (hold full)"); }
                else if (room <= 1e-9) note.next().append("hold full, no fishing");
            }
            // seabed mining (issue #112): iron from the hex's nodules into the hold. The same shape as
            // fishing because it is the same mission — roam, work the water you are over, come home.
            if (cls.miningRateOr0() > 0 && here.terrain() == Terrain.OCEAN && eff > 0) {
                double room = Math.max(0, cls.hold() - ship.load());
                double ore = Math.min(room, cls.miningRateOr0() * here.resource("minerals") * ctx.etus * sc.oreRate() * eff);
                ore = ctx.led().produceAtSea(ctx.com.index("iron"), ore);
                if (ore > 0) { ship = ship.withStock(ship.stock().plus(ctx.com.index("iron"), ore)).tally(Ship.MINED + "iron", ore); note.next().append("mined ").append(Ledger.q(ore)).append(" iron"); if (room - ore < 1e-9) note.last().append(" (hold full)"); }
                else if (room <= 1e-9) note.next().append("hold full, no mining");
                else if (here.resource("minerals") <= 0) note.next().append("no nodules here");
            }
            // luxury: happiness while at sea
            if (cls.happinessOr0() > 0 && here.terrain() == Terrain.OCEAN && eff > 0) {
                double h = cls.happinessOr0() * ctx.etus * eff;
                ctx.led().level[ship.owner()][3] += h;
                ship = ship.tally(Ship.HAPPINESS, h);
                note.next().append("cruised: +").append(Ledger.q(h)).append(" happiness");
            }
            // refuel (issue #65): a harbour pumps from its own stock, a tanker from its hold at sea. First,
            // before anything decides where she goes, so the fuel check below sees the tank she really has.
            if (sc.fuel() && docked) ship = refuel(ctx, ship, cls, hi, note);
            else if (sc.fuel()) ship = refuelAtSea(ctx, ship, cls, out, note);
            // a tanker never runs dry with petrol in its own hold (issue #67)
            if (sc.fuel()) ship = refuelFromOwnHold(ctx, ship, cls, note);
            boolean refitting = false;
            // limp home (Richard 2026-09-13): a hull at sea worn to the least she can sail on makes for
            // the nearest harbour of her owner's, whatever she was doing, and keeps her orders for after
            boolean limping = !docked && floor > 0 && ship.efficiency() <= floor + 1e-9;
            if (limping) {
                Coord haven = nearestHarbour(ctx, ship, harbours.computeIfAbsent(ship.owner(), o -> ownHarbors(ctx, o)));
                if (haven == null) { note.next().append("worn out, and no harbour of yours she can reach"); ship = ship.withDest(null); }
                else { if (!haven.equals(ship.dest())) note.next().append("worn out: limping home to ").append(haven); ship = ship.withDest(haven); }
            } else if (ship.handLeg()) {
                // sent somewhere by hand (issues #201, #205): her standing order waits until she arrives
                if (ship.dest() == null) note.next().append("holding; her ").append(ship.orderLabel()).append(" is paused");
            } else if (ship.rescuing()) {
                ship = runRescue(ctx, sc, ship, si, out, harbours, told, note);
            } else if (ship.lane() != null) {
                ship = runLane(ctx, ship, cls, si, out, note);
            } else if (ship.supplying() && ship.home() != null) {
                refitting = refitDue(sc, ship, docked);
                if (refitting) ship = goForRefit(ship, docked, note);
                else ship = runSupply(ctx, ship, cls, si, out, harbours, note);
            } else if (ship.onMilitaryMission() && ship.home() != null) {
                ship = runMilitary(ctx, sc, cls, ship, docked, si, out, note);
            } else if (ship.roaming() && ship.home() != null) {
                // one mission, two things to look for (issue #112): land the load at home, then roam the
                // water, turning for home when the hold fills. Only the weighting differs.
                boolean mining = Ship.MINE.equals(ship.mission());
                var fc = mining ? sc.miningOrDefault() : sc.fishingOrDefault();
                // too worn to be out here: break off and make for home. The mission is kept, so the
                // ship goes back to work by itself — but not until it is fully refitted, so a worn
                // fleet is a real cost and not a rounding error.
                if (refitDue(sc, ship, docked)) {
                    if (docked && ship.load() > 0) ship = unload(ctx, ship, here, hi, null, note);
                    ship = goForRefit(ship, docked, note);
                } else {
                    if (ship.at().equals(ship.home()) && ship.load() > 0) ship = unload(ctx, ship, here, hi, null, note);
                    boolean full = ship.load() >= cls.hold() * fc.returnWhenHoldFraction() - 1e-9;
                    if (full) { if (!ship.home().equals(ship.dest())) note.next().append("hold ").append(Ledger.q(100 * ship.load() / cls.hold())).append("% full, heading home to ").append(ship.home()); ship = ship.withDest(ship.home()); }
                    else if (ship.dest() == null || ship.at().equals(ship.dest()) || ship.dest().equals(ship.home())) {
                        Coord next = pickWaters(ctx, ship, fc, mining);
                        if (next == null && !ship.at().equals(ship.home())) {
                            // away from home — put into another harbour for fuel, or limping — with no water of
                            // her own grounds in a leg's reach: she goes home and works from there, instead of
                            // sitting at a stranger's quay for ever (game 82, 2026-09-14)
                            if (!ship.home().equals(ship.dest())) note.next().append("her ").append(mining ? "nodule fields" : "fishing grounds").append(" are around ").append(ship.home()).append(", out of reach from here; heading home");
                            ship = ship.withDest(ship.home());
                        }
                        else if (next == null) { note.next().append(mining ? "no nodule fields within " : "no fishing grounds within ").append(fc.radius()).append(" of ").append(ship.home()); ship = ship.withDest(null); }
                        else ship = ship.withDest(next);
                    }
                }
            } else if (!docked && cls.tender() && ship.mission() == null && ship.lane() == null && ship.dest() == null) {
                // a tender with no orders waits on call in port, not at sea wearing out (Richard 2026-09-14):
                // she makes for the harbour nearest by sea, restocks there, and answers calls on the way
                int[] dist = harbourDistances.computeIfAbsent(ship.owner(), o -> SeaRoutes.harbourDistances(ctx.snap, ctx.cfg, o));
                List<Coord> home = SeaRoutes.pathHome(ctx.snap, ctx.cfg, ship.owner(), ship.at(), dist);
                if (home != null && home.size() > 1) {
                    Coord port = home.get(home.size() - 1);
                    note.next().append("on call: making for ").append(port).append(" to wait there");
                    ship = ship.withDest(port);
                }
            } else if (docked && sc.autoUnloadInHarbor() && cls.worksTheSea() && ship.load() > 0) {
                ship = unload(ctx, ship, here, hi, null, note);   // home from the water, the load goes ashore
            }
            // sign on a crew (issue #66): only a harbour can, and only from the people who are there
            if (sc.crews() && docked) ship = muster(ctx, ship, cls, hi, note);
            // rearm (issue #68): a warship in harbour takes on guns up to what she mounts and shells up to her magazine
            if (docked && sc.combat() != null && cls.armed()) ship = rearm(ctx, ship, cls, hi, note);
            // restock (issue #182): a tender waiting in harbour loads what she will need for the next call
            if (docked && cls.tender() && ship.mission() == null && ship.lane() == null) ship = restockTender(ctx, sc, ship, cls, hi, note);
            Coord bingo = null;   // the harbour she was turned for because of her fuel, this update
            // a ship does not leave port until her tank is full (Richard 2026-09-14)
            boolean fillingUp = sc.fuel() && docked && cls.tankOr0() - ship.fuel() >= 1 && ship.dest() != null && !ship.dest().equals(ship.at());
            if (fillingUp) {
                int pet = ctx.com.index(sc.fuelId());
                double left = ctx.sector(hi).stock().get(pet) + ctx.led().st(hi, pet);
                note.next().append("waiting in harbour to fill her tank (").append(Ledger.q(ship.fuel())).append(" of ").append(Ledger.q(cls.tankOr0())).append(')')
                    .append(left < 1 ? "; the harbour has no " + sc.fuelId() + " left" : "");
            }
            // sail
            if (!fillingUp && ship.dest() != null && !ship.dest().equals(ship.at())) {
                List<Coord> path = SeaRoutes.path(ctx.snap, ctx.cfg, ship.owner(), ship.at(), ship.dest());
                if (path == null) note.next().append("no sea route to ").append(ship.dest());
                else {
                    int range = (int) Math.floor(ship.mobility());
                    if (limping) range = Math.max(range, 1);        // however worn, she makes a hex an update toward harbour
                    int hops = Math.min(range, path.size() - 1);
                    // short-handed is not going anywhere (issue #66)
                    if (sc.crews() && ship.crew() < cls.crewOr0()) {
                        note.next().append("short-handed: ").append(Ledger.q(ship.crew())).append(" of ").append(Ledger.q(cls.crewOr0()))
                                   .append(' ').append(ctx.com.id(crewCommodity(ctx, cls))).append(" aboard");
                        hops = 0;
                    }
                    // a dry tank holds the ship where it is (issue #65)
                    double perHex = sc.fuel() ? cls.fuelPerHexOr0() : 0;
                    int fuelled = perHex > 0 ? (int) Math.floor(ship.fuel() / perHex) : hops;
                    if (perHex > 0 && fuelled < hops) hops = Math.max(0, fuelled);
                    // she never sails further than she can get back from (Richard 2026-09-14, ship #30):
                    // however her orders chose the leg, in port or at sea, bound for a harbour or not, she
                    // stops where the fuel left still takes her to the nearest harbour; and a ship already
                    // out of reach of one makes for the nearest as far as her tank will carry her
                    if (perHex > 0 && hops > 0) {
                        int[] dist = harbourDistances.computeIfAbsent(ship.owner(), o -> SeaRoutes.harbourDistances(ctx.snap, ctx.cfg, o));
                        if (dist[ctx.idx(ship.at())] != Integer.MAX_VALUE) {
                            // a tender can fill her own tank from her hold, so the petrol aboard is range too
                            double range_fuel = ship.fuel() + (cls.tender() ? ship.stock().get(ctx.com.index(sc.fuelId())) : 0);
                            int safe = SeaRoutes.safeHops(ctx.snap, path, hops, dist, range_fuel, perHex, sc.missionsOrDefault().reserve());
                            if (safe == hops) { /* the whole leg, and home again after */ }
                            else if (safe > 0) {
                                note.next().append("sailed only ").append(safe).append(" of ").append(hops).append(" hexes: no further than her fuel will bring her back from");
                                hops = safe;
                            } else if (docked) {
                                note.next().append("stays in port: ").append(ship.dest()).append(" is further than her fuel would bring her back from");
                                hops = 0;
                            } else {
                                // by sea, along the same distances the check used — not to the harbour
                                // nearest as the crow flies, which can be further by water than her tank
                                List<Coord> home = SeaRoutes.pathHome(ctx.snap, ctx.cfg, ship.owner(), ship.at(), dist);
                                Coord haven = home == null ? null : home.get(home.size() - 1);
                                if (home != null) {
                                    bingo = haven;
                                    if (!haven.equals(ship.dest())) note.next().append("low on fuel (").append(Ledger.q(ship.fuel())).append(" of ").append(Ledger.q(cls.tankOr0())).append("): making for ").append(haven);
                                    ship = ship.withDest(haven);
                                    path = home;
                                    hops = Math.min(Math.min(range, path.size() - 1), fuelled);
                                }
                            }
                        }
                    }
                    // a hostile blockade on station stops her where she meets it (issue #68)
                    var blocked = org.hastingtx.empire.engine.combat.Blockade.limit(ctx.snap, ctx.cfg, ship, path, hops, ctx.snap.updateNumber());
                    if (blocked.by() != null) {
                        hops = blocked.hops();
                        note.next().append("stopped by ").append(ctx.country(blocked.by().owner()).name()).append("'s blockade at ").append(path.get(hops));
                    }
                    if (blocked.by() != null && hops <= 0) { /* already said so */ }
                    else if (hops <= 0 && sc.crews() && ship.crew() < cls.crewOr0()) { /* already said so */ }
                    else if (hops <= 0 && perHex > 0 && ship.fuel() < perHex) {
                        note.next().append("out of fuel, holding at ").append(ship.at());
                        if (!docked) note.last().append(tenderFor(ctx, ship, out, si) != null ? "; a tender is on her way" : "; distress call sent");
                    }
                    else if (hops <= 0) note.next().append("too unfit to sail (").append(Ledger.q(ship.efficiency())).append("%)");
                    else {
                        Coord to = path.get(hops);
                        ship = ship.withAt(to).withMobility(ship.mobility() - hops);
                        if (perHex > 0) {
                            double burned = hops * perHex;
                            ship = ship.withFuel(ship.fuel() - burned);
                            ctx.led().destroyed(ctx.com.index(sc.fuelId()), burned);   // burned fuel leaves the world
                        }
                        note.next().append("sailed ").append(hops).append(hops == 1 ? " hex" : " hexes").append(" to ").append(to);
                        if (perHex > 0 && ship.fuel() < perHex) note.last().append(" (tank dry)");
                        if (to.equals(ship.dest())) {
                            note.last().append(", arrived");
                            // the harbour's business is done the update she gets there, not the one after
                            // (issue #67): a lane unloads on arrival and a supply ship takes the next job
                            if (limping || bingo != null) {
                                // turned for a harbour on the way somewhere: a hand leg ends here, and her standing
                                // order takes her over again once she is fit and fuelled (issue #213)
                                if (ship.handLeg() && ship.orderLabel() != null) note.last().append("; her ").append(ship.orderLabel()).append(" resumes");
                                ship = ship.withDest(null).withHandLeg(false);
                            }
                            else if (ship.handLeg()) { ship = ship.withDest(null).withHandLeg(false); if (ship.orderLabel() != null) note.last().append("; her ").append(ship.orderLabel()).append(" resumes"); }
                            else if (ship.rescuing()) ship = runRescue(ctx, sc, ship.withDest(null), si, out, harbours, told, note);   // alongside the update she arrives
                            else if (ship.lane() != null) ship = runLane(ctx, ship, cls, si, out, note);
                            else {
                                ship = ship.withDest(null);
                                if (ship.supplying() && ship.home() != null && !refitting) ship = runSupply(ctx, ship, cls, si, out, harbours, note);
                            }
                        }
                    }
                }
            } else if (!fillingUp && ship.dest() != null) {
                if (ship.handLeg()) ship = ship.withDest(null).withHandLeg(false);   // already where she was sent: the order resumes
                else if (ship.lane() == null) ship = ship.withDest(null);
            }
            // upkeep
            if (cls.upkeepPerUpdate() != null) for (var e : cls.upkeepPerUpdate().entrySet()) if (e.getKey().equals("cash")) ctx.led().cash[ship.owner()] -= e.getValue();
            List<String> lines = note.isEmpty() ? List.of(docked ? "in harbour" : "holding") : note.lines();
            ctx.led().shipNotes.put(ship.id(), lines);
            out.add(ship.withNote(String.join("; ", lines)));
        }
        ctx.ships.clear(); ctx.ships.addAll(out);
    }

    /**
     * What a ship did this update, a line at a time (issue #67). The lines are its logbook; joined, they
     * are the one-line note the fleet listing has always shown, character for character.
     */
    static final class Log {
        private final List<StringBuilder> lines = new ArrayList<>();
        /** Start a new line. */
        StringBuilder next() { StringBuilder b = new StringBuilder(); lines.add(b); return b; }
        /** Add to the line just written — "sailed 3 hexes to 4,5" + ", arrived". */
        StringBuilder last() { return lines.isEmpty() ? next() : lines.get(lines.size() - 1); }
        boolean isEmpty() { return lines.isEmpty(); }
        List<String> lines() { return lines.stream().map(StringBuilder::toString).toList(); }
    }

    // ---------------------------------------------------------------------------------- refits

    /** Worn to the refit line, or in dock and not yet back to full: either way, no work (Richard 2026-09-11). */
    private static boolean refitDue(UnitsCfg.ShipsCfg sc, Ship ship, boolean docked) {
        return ship.efficiency() <= sc.refitAtOrBelow() || (docked && ship.efficiency() < sc.refitUpTo());
    }

    private static Ship goForRefit(Ship ship, boolean docked, Log note) {
        if (docked) {
            note.next().append("refitting; it will not go out again until it is at 100%");
            return ship.withDest(null);
        }
        note.next().append("too worn to work, making for ").append(ship.home());
        return ship.withDest(ship.home());
    }

    // ---------------------------------------------------------------------------------- lanes

    /**
     * Do the lane's business if the ship is at the end it is bound for, and point it at the next end.
     * Called before sailing and again on arrival, so a ship that reaches the far harbour unloads there
     * that same update (issue #67) instead of sitting at the quay for one.
     *
     * <p>A lane with no cargo named keeps the far end's thresholds topped up, as a rail lane does
     * (issue #70): it loads only what that harbour is short of, less what is already on its way there.
     * A ship with nothing to carry waits at the loading end rather than sailing an empty hull there and
     * back on petrol.
     */
    private static Ship runLane(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, int si, List<Ship> out, Log note) {
        Ship.Lane lane = ship.lane();
        Coord end = lane.target();
        if (ship.at().equals(end)) {
            Sector harbor = ctx.sector(ctx.idx(end));
            int hi = ctx.idx(end);
            if (!ownHarbor(ctx, ship.owner(), harbor)) {
                note.next().append(end).append(" is no longer one of your harbours; the lane waits");
                return ship.withDest(null);
            }
            if (!lane.outbound()) {
                double[] limit = null;
                if (lane.cargo().isEmpty()) {
                    limit = new double[ctx.com.size()];
                    for (int c = 0; c < ctx.com.size(); c++)
                        if (carries(ctx, cls, c)) limit[c] = Math.max(0, shortfall(ctx, lane.to(), ship.owner(), c) - inbound(ctx, ship, lane.to(), c, si, out, true));
                }
                ship = load(ctx, ship, cls, harbor, hi, lane.cargo(), limit, note);
                if (ship.load() < 1) {
                    if (lane.cargo().isEmpty()) note.last().append(" — ").append(lane.to()).append(" is short of nothing it can carry; waiting");
                    return ship.withDest(null);
                }
                ship = ship.withLane(lane.turned(true));
            } else {
                ship = unload(ctx, ship, harbor, hi, null, note);
                ship = ship.withLane(lane.turned(false));
            }
        }
        return ship.withDest(ship.lane().target());
    }

    // ---------------------------------------------------------------------------------- supply

    /**
     * The supply round (issue #67). Nobody orders a shipment: a harbour's thresholds say what it wants,
     * and a ship on supply goes and gets it from whichever of your harbours has it to spare.
     *
     * <p>In a harbour she first lands what that harbour is short of — only what it is short of, so the
     * rest can go on to the next one — then takes on what other harbours want and this one can spare.
     * Then she picks where to go: with cargo, the harbour that wants it most; empty, the harbour that
     * can fill the most pressing want. Nothing wanted anywhere keeps her where she is in harbour, or
     * sends her home from sea.
     *
     * <p>Wants are counted net of cargo already bound there in any of your ships, so two supply ships
     * do not both answer the same shortage. It is all recomputed every update from the thresholds, so
     * a player who changes one changes where the fleet goes, with no orders to cancel.
     */
    private static Ship runSupply(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, int si, List<Ship> out, Map<Integer, List<Sector>> harbours, Log note) {
        int owner = ship.owner();
        List<Sector> mine = harbours.computeIfAbsent(owner, o -> ownHarbors(ctx, o));
        Sector here = ctx.sector(ctx.idx(ship.at()));
        int hi = ctx.idx(ship.at());
        boolean docked = ownHarbor(ctx, owner, here);
        int n = ctx.com.size();

        if (docked) {
            if (ship.load() >= 1) {
                double[] limit = new double[n];
                boolean any = false;
                for (int c = 0; c < n; c++) if (ship.stock().get(c) >= 1) { limit[c] = shortfall(ctx, here.at(), owner, c); any |= limit[c] >= 1; }
                if (any) ship = unload(ctx, ship, here, hi, limit, note);
            }
            if (cls.hold() - ship.load() >= 1) {
                double[] limit = new double[n];
                boolean any = false;
                for (int c = 0; c < n; c++) {
                    if (!carries(ctx, cls, c) || spare(ctx, here.at(), owner, c) < 1) continue;
                    for (Sector d : mine) if (!d.at().equals(here.at())) limit[c] += Math.max(0, shortfall(ctx, d.at(), owner, c) - inbound(ctx, ship, d.at(), c, si, out, true));
                    any |= limit[c] >= 1;
                }
                if (any) ship = load(ctx, ship, cls, here, hi, List.of(), limit, note);
            }
        }

        List<Job> jobs = new ArrayList<>();
        if (ship.load() >= 1) {
            // she has cargo: who wants it
            for (Sector d : mine) {
                if (docked && d.at().equals(here.at())) continue;
                for (int c = 0; c < n; c++) {
                    if (ship.stock().get(c) < 1) continue;
                    double want = shortfall(ctx, d.at(), owner, c) - inbound(ctx, ship, d.at(), c, si, out, false);
                    if (want >= 1) jobs.add(new Job(ship.at(), d.at(), c, want, thresholds(ctx, d.at(), owner, c), Hex.distance(ctx.snap, ship.at(), d.at())));
                }
            }
        } else {
            // empty: a want somewhere, and a harbour that can fill it
            for (Sector d : mine) for (int c = 0; c < n; c++) {
                if (!carries(ctx, cls, c)) continue;
                double want = shortfall(ctx, d.at(), owner, c) - inbound(ctx, ship, d.at(), c, si, out, true);
                if (want < 1) continue;
                for (Sector s : mine) {
                    if (s.at().equals(d.at()) || (docked && s.at().equals(here.at())) || spare(ctx, s.at(), owner, c) < 1) continue;
                    jobs.add(new Job(s.at(), d.at(), c, want, thresholds(ctx, d.at(), owner, c),
                            Hex.distance(ctx.snap, ship.at(), s.at()) + Hex.distance(ctx.snap, s.at(), d.at())));
                }
            }
        }
        jobs.sort(ShipStep::compareJobs);
        for (Job j : jobs) {
            if (SeaRoutes.path(ctx.snap, ctx.cfg, owner, ship.at(), j.from()) == null) continue;
            if (!j.from().equals(ship.at()) && SeaRoutes.path(ctx.snap, ctx.cfg, owner, j.from(), j.to()) == null) continue;
            Coord go = ship.load() >= 1 ? j.to() : j.from();
            if (!go.equals(ship.dest())) {
                if (ship.load() >= 1) note.next().append("bound for ").append(j.to()).append(", short of ").append(Ledger.q(j.want())).append(' ').append(ctx.com.id(j.commodity()));
                else note.next().append("bound for ").append(j.from()).append(" to fetch ").append(ctx.com.id(j.commodity())).append(" for ").append(j.to());
            }
            return ship.withDest(go);
        }
        if (docked) {
            if (ship.dest() != null || note.isEmpty()) note.next().append(ship.load() >= 1 ? "no harbour of yours is short of what she carries; waiting" : "no harbour of yours is short of anything she can carry; waiting");
            return ship.withDest(null);
        }
        if (!ship.home().equals(ship.dest())) note.next().append("nothing to carry, making for ").append(ship.home());
        return ship.withDest(ship.home());
    }

    /**
     * A shortage a supply ship could answer: {@code want} of {@code commodity} at {@code to}, fetched
     * from {@code from} (her own position when she already has it aboard). {@code threshold} is what
     * {@code to} asked for, so {@code want / threshold} is how empty it is; {@code hexes} is the trip.
     */
    record Job(Coord from, Coord to, int commodity, double want, double threshold, int hexes) {}

    /**
     * Which shortage a supply ship answers first; the smaller sorts first. This is the one real policy in
     * the supply mission, and every other part of it is bookkeeping.
     *
     * <p>Most nearly empty first — a harbour at 10% of its threshold before one at 80%, however large
     * either number is, since an island down to its last tenth of food is the emergency. Then the shorter
     * trip, so a fleet does not cross the map for what is nearby. Then place and commodity, so the answer
     * never depends on the order the harbours were found in.
     */
    private static final Comparator<Job> JOB_ORDER = Comparator
            .comparingDouble((Job j) -> j.threshold() > 0 ? -j.want() / j.threshold() : 0)
            .thenComparingInt(Job::hexes)
            .thenComparing(Job::to)
            .thenComparing(Job::from)
            .thenComparingInt(Job::commodity);

    static int compareJobs(Job a, Job b) {
        return JOB_ORDER.compare(a, b);
    }

    /** Your harbours, in canonical sector order. */
    private static List<Sector> ownHarbors(Ctx ctx, int owner) {
        List<Sector> out = new ArrayList<>();
        for (Sector s : ctx.snap.sectors()) if (ownHarbor(ctx, owner, s)) out.add(s);
        return out;
    }

    private static boolean ownHarbor(Ctx ctx, int owner, Sector s) { return s.owner() == owner && SeaRoutes.isHarbor(ctx.cfg, s); }

    /** What a harbour and its dockside warehouses are short of their thresholds, now (issue #67). */
    static double shortfall(Ctx ctx, Coord harbor, int owner, int c) {
        double sum = 0;
        for (int i : dockside(ctx, ctx.sector(ctx.idx(harbor)), owner)) {
            Sector s = ctx.sector(i);
            if (s.hasThreshold(c)) sum += Math.max(0, s.threshold(c) - (s.stock().get(c) + ctx.led().st(i, c)));
        }
        return Math.floor(sum);
    }

    /** What a harbour and its dockside warehouses asked for. */
    private static double thresholds(Ctx ctx, Coord harbor, int owner, int c) {
        double sum = 0;
        for (int i : dockside(ctx, ctx.sector(ctx.idx(harbor)), owner)) { Sector s = ctx.sector(i); if (s.hasThreshold(c)) sum += s.threshold(c); }
        return sum;
    }

    /** What a harbour and its dockside warehouses hold above their own thresholds — what a threshold keeps back. */
    static double spare(Ctx ctx, Coord harbor, int owner, int c) {
        double sum = 0;
        for (int i : dockside(ctx, ctx.sector(ctx.idx(harbor)), owner)) {
            Sector s = ctx.sector(i);
            sum += Math.max(0, s.stock().get(c) + ctx.led().st(i, c) - (s.hasThreshold(c) ? s.threshold(c) : 0));
        }
        return Math.floor(sum);
    }

    /**
     * Cargo of {@code c} already on its way to {@code harbor} in the owner's other ships — bound there,
     * or on a lane whose next end it is. Ships this step has already moved are read from {@code done};
     * the rest from the snapshot, so the count is the same whichever ship asks. {@code self} says
     * whether this ship's own hold counts: it does when deciding what to load, not when deciding where
     * to take what is already aboard.
     */
    private static double inbound(Ctx ctx, Ship ship, Coord harbor, int c, int si, List<Ship> done, boolean self) {
        double sum = self && harbor.equals(ship.dest()) ? ship.stock().get(c) : 0;
        for (int k = 0; k < ctx.ships.size(); k++) {
            if (k == si) continue;
            Ship o = k < done.size() ? done.get(k) : ctx.ships.get(k);
            if (o.owner() != ship.owner()) continue;
            Coord bound = o.lane() != null ? o.lane().target() : o.dest();
            if (harbor.equals(bound)) sum += o.stock().get(c);
        }
        return sum;
    }

    /** The nearest harbour of her owner's with a sea route to it, by hexes, ties in canonical order. */
    private static Coord nearestHarbour(Ctx ctx, Ship ship, List<Sector> mine) { return nearestHarbour(ctx, ship.owner(), ship.at(), mine); }

    private static Coord nearestHarbour(Ctx ctx, int owner, Coord from, List<Sector> mine) {
        Coord best = null; int bestD = Integer.MAX_VALUE;
        for (Sector h : mine) {
            int d = Hex.distance(ctx.snap, from, h.at());
            if (d >= bestD) continue;
            if (SeaRoutes.path(ctx.snap, ctx.cfg, owner, from, h.at()) == null) continue;
            best = h.at(); bestD = d;
        }
        return best;
    }

    // ---------------------------------------------------------------------------------- tenders

    /**
     * Distress calls, answered (issue #182). A ship at sea that cannot make a hex for want of fuel calls.
     * Each call nobody is already answering takes the free tender of her owner's with the shortest sea
     * route to her: a tender with no orders, not worn to the refit line. Read from the ships as they
     * stood at the start of the step, and the calls taken in ship-id order, so who answers whom does not
     * depend on processing order.
     */
    private static void dispatchTenders(Ctx ctx, UnitsCfg.ShipsCfg sc, Map<Integer, List<Sector>> harbours, Map<Long, List<String>> told) {
        if (!sc.fuel()) return;
        java.util.Set<Long> answered = new java.util.HashSet<>();
        boolean anyTender = false;
        for (Ship t : ctx.ships) if (sc.shipClass(t.cls()).tender()) { anyTender = true; if (t.rescuing()) answered.add(t.ward()); }
        if (!anyTender) return;
        for (Ship d : ctx.ships) {
            UnitsCfg.ShipClassCfg dc = sc.shipClass(d.cls());
            if (answered.contains(d.id()) || d.fuel() >= dc.fuelPerHexOr0() || dc.fuelPerHexOr0() <= 0) continue;
            if (ownHarbor(ctx, d.owner(), ctx.snap.sector(d.at()))) continue;
            int best = -1, bestLen = Integer.MAX_VALUE;
            for (int k = 0; k < ctx.ships.size(); k++) {
                Ship t = ctx.ships.get(k);
                UnitsCfg.ShipClassCfg tc = sc.shipClass(t.cls());
                if (!tc.tender() || t.owner() != d.owner() || t.id() == d.id()) continue;
                // free: no mission, no lane, not worn out, and going nowhere but into one of her own harbours —
                // a tender on her way home to wait is still on call
                if (t.mission() != null || t.lane() != null || t.efficiency() <= sc.refitAtOrBelow()) continue;
                if (t.dest() != null && !ownHarbor(ctx, t.owner(), ctx.snap.sector(t.dest()))) continue;
                List<Coord> p = SeaRoutes.path(ctx.snap, ctx.cfg, t.owner(), t.at(), d.at());
                if (p == null) continue;
                if (p.size() < bestLen) { best = k; bestLen = p.size(); }
            }
            if (best < 0) continue;
            Ship t = ctx.ships.get(best);
            Coord home = ownHarbor(ctx, t.owner(), ctx.snap.sector(t.at())) ? t.at()
                    : nearestHarbour(ctx, t, harbours.computeIfAbsent(t.owner(), o -> ownHarbors(ctx, o)));
            ctx.ships.set(best, t.withOrders(Ship.RESCUE, home, List.of(), d.id()));
            answered.add(d.id());
            told.computeIfAbsent(t.id(), k -> new ArrayList<>()).add("heard a distress call from ship #" + d.id() + " at " + d.at() + " and answered it");
        }
    }

    /** The tender answering this ship's call, if one is. */
    private static Ship tenderFor(Ctx ctx, Ship ship, List<Ship> done, int si) {
        for (int k = 0; k < ctx.ships.size(); k++) {
            Ship t = k < done.size() ? done.get(k) : ctx.ships.get(k);
            if (k != si && t.rescuing() && t.ward() == ship.id() && t.owner() == ship.owner()) return t;
        }
        return null;
    }

    /**
     * A tender on a call (issue #182). She sails for the ship; alongside, she fills that ship's tank from
     * her hold and patches her hull up to the limp-home line with lcm, then makes for home, where she
     * restocks and waits for the next call. A call that no longer needs answering — the ship gone, or
     * already able to sail — sends her home too.
     */
    private static Ship runRescue(Ctx ctx, UnitsCfg.ShipsCfg sc, Ship tender, int si, List<Ship> out, Map<Integer, List<Sector>> harbours, Map<Long, List<String>> told, Log note) {
        Coord home = tender.home() != null ? tender.home() : nearestHarbour(ctx, tender, harbours.computeIfAbsent(tender.owner(), o -> ownHarbors(ctx, o)));
        int wi = -1;
        for (int k = 0; k < ctx.ships.size(); k++) {
            Ship o = k < out.size() ? out.get(k) : ctx.ships.get(k);
            if (k != si && o.id() == tender.ward() && o.owner() == tender.owner()) { wi = k; break; }
        }
        if (wi < 0) {
            note.next().append("the ship she was sent for, #").append(tender.ward()).append(", is gone; going home");
            return tender.withMission(null, null).withDest(home);
        }
        Ship ward = wi < out.size() ? out.get(wi) : ctx.ships.get(wi);
        UnitsCfg.ShipClassCfg wc = sc.shipClass(ward.cls());
        double floor = sc.limpFloor(wc, ward.tech());
        boolean needsFuel = ward.fuel() < wc.fuelPerHexOr0();
        if (!ward.at().equals(tender.at())) {
            if (!needsFuel) {
                note.next().append("ship #").append(ward.id()).append(" no longer needs her; going home");
                return tender.withMission(null, null).withDest(home);
            }
            if (!ward.at().equals(tender.dest())) note.next().append("answering a distress call from ship #").append(ward.id()).append(" at ").append(ward.at());
            return tender.withDest(ward.at());
        }

        // alongside: fuel first, then a patch
        int pet = ctx.com.index(sc.fuelId()), lcm = ctx.com.index("lcm");
        double give = Math.floor(Math.min(wc.tankOr0() - ward.fuel(), tender.stock().get(pet)));
        StringBuilder did = new StringBuilder();
        if (give >= 1) {
            ward = ward.withFuel(ward.fuel() + give);
            tender = tender.withStock(tender.stock().plus(pet, -give)).tally(Ship.FUEL_GIVEN, give);
            did.append("filled her tank with ").append(Ledger.q(give)).append(' ').append(sc.fuelId());
        }
        double cost = sc.tendersOrDefault().patchCost();
        if (ward.efficiency() < floor && cost > 0) {
            double use = Math.min(Math.ceil((floor - ward.efficiency()) * cost), Math.floor(tender.stock().get(lcm)));
            if (use >= 1) {
                double points = Math.min(floor - ward.efficiency(), use / cost);
                ward = ward.withEfficiency(ward.efficiency() + points);
                tender = tender.withStock(tender.stock().plus(lcm, -use));
                ctx.led().destroyed(lcm, use);                         // spent on the hull, gone from the world
                did.append(did.isEmpty() ? "" : " and ").append("patched her hull to ").append(Ledger.q(ward.efficiency())).append("% with ").append(Ledger.q(use)).append(" lcm");
            }
        }
        if (wi < out.size()) out.set(wi, ward); else ctx.ships.set(wi, ward);
        if (did.isEmpty()) {
            note.next().append("reached ship #").append(ward.id()).append(" with nothing aboard to give her; going home to restock");
            return tender.withMission(null, null).withDest(home);
        }
        tender = tender.tally(Ship.RESCUES, 1);
        note.next().append("reached ship #").append(ward.id()).append(": ").append(did).append("; going home to ").append(home);
        String line = "tender #" + tender.id() + " came alongside: " + did.toString().replace("her tank", "the tank").replace("her hull", "the hull");
        if (wi < si) {
            List<String> lines = new ArrayList<>(ctx.led().shipNotes.getOrDefault(ward.id(), List.of()));
            lines.add(line);
            ctx.led().shipNotes.put(ward.id(), lines);
            out.set(wi, ward.withNote(ward.note() == null || ward.note().isBlank() ? line : ward.note() + "; " + line));
        } else told.computeIfAbsent(ward.id(), k -> new ArrayList<>()).add(line);
        return tender.withMission(null, null).withDest(home);
    }

    /** A tender waiting in harbour takes on {@code tenders.restock} from the harbour's own stock, up to her hold. */
    private static Ship restockTender(Ctx ctx, UnitsCfg.ShipsCfg sc, Ship ship, UnitsCfg.ShipClassCfg cls, int hi, Log note) {
        StringBuilder took = new StringBuilder();
        for (var e : sc.tendersOrDefault().restockOrDefault().entrySet()) {
            if (!ctx.com.has(e.getKey())) continue;
            int c = ctx.com.index(e.getKey());
            double room = Math.min(e.getValue() - ship.stock().get(c), cls.hold() - ship.load());
            if (room < 1) continue;
            double got = fromQuay(ctx, ship, hi, c, room);
            if (got <= 0) continue;
            ship = ship.withStock(ship.stock().plus(c, got));
            took.append(took.isEmpty() ? "" : " and ").append(Ledger.q(got)).append(' ').append(e.getKey());
        }
        if (!took.isEmpty()) note.next().append("stocked up with ").append(took).append(" for the next call");
        return ship;
    }

    // ---------------------------------------------------------------------------------- military missions

    /**
     * Where a warship on a mission goes this update (issue #68). First the supplies: worn to the refit
     * line, or low on shells, fuel or crew, she makes for home, and in harbour she stays until she is
     * fit to go out again — the rearm, refuel and muster later in this step fill her up. Then the
     * mission: the next patrol waypoint, the station, her charge, or somewhere new to look.
     */
    private static Ship runMilitary(Ctx ctx, UnitsCfg.ShipsCfg sc, UnitsCfg.ShipClassCfg cls, Ship ship, boolean docked, int si, List<Ship> out, Log note) {
        if (refitDue(sc, ship, docked)) return goForRefit(ship, docked, note);
        String low = lowOn(ctx, sc, cls, ship, docked);
        if (low != null) {
            if (docked) { note.next().append("in harbour for ").append(low); return ship.withDest(null); }
            if (!ship.home().equals(ship.dest())) note.next().append("low on ").append(low).append(", making for ").append(ship.home());
            return ship.withDest(ship.home());
        }
        Coord before = ship.dest();
        switch (ship.mission()) {
            case Ship.PATROL -> {
                List<Coord> route = ship.route();
                if (route.isEmpty()) return ship.withDest(null);
                if (ship.dest() == null || ship.at().equals(ship.dest()) || !route.contains(ship.dest())) {
                    int here = route.indexOf(ship.at());
                    Coord next;
                    if (here >= 0) next = route.get((here + 1) % route.size());
                    else {
                        next = route.get(0);
                        for (Coord p : route) if (Hex.distance(ctx.snap, ship.at(), p) < Hex.distance(ctx.snap, ship.at(), next)) next = p;
                    }
                    ship = ship.withDest(next);
                }
                if (!Objects.equals(before, ship.dest())) note.next().append("on patrol, bound for ").append(ship.dest());
            }
            case Ship.BLOCKADE, Ship.INTERDICT -> {
                Coord station = ship.station();
                if (station == null) return ship.withDest(null);
                if (ship.at().equals(station)) { note.next().append("on station at ").append(station); return ship.withDest(null); }
                if (!station.equals(ship.dest())) note.next().append("making for her station at ").append(station);
                ship = ship.withDest(station);
            }
            case Ship.ESCORT -> {
                Ship charge = null;
                for (int k = 0; k < ctx.ships.size(); k++) {
                    Ship o = k < out.size() ? out.get(k) : ctx.ships.get(k);
                    if (k != si && o.id() == ship.ward() && o.owner() == ship.owner()) { charge = o; break; }
                }
                if (charge == null) {
                    note.next().append("her charge, ship #").append(ship.ward()).append(", is gone; making for ").append(ship.home());
                    return ship.withMission(null, null).withDest(ship.home());
                }
                ship = ship.withDest(ship.at().equals(charge.at()) ? null : charge.at());
                if (!Objects.equals(before, ship.dest())) note.next().append(ship.dest() == null ? "with ship #" + charge.id() : "following ship #" + charge.id() + " to " + charge.at());
            }
            default -> {   // search
                if (ship.dest() == null || ship.at().equals(ship.dest()) || ship.dest().equals(ship.home())) {
                    Coord next = pickSearch(ctx, sc, ship);
                    if (next == null) { note.next().append("no water to search within ").append(sc.missionsOrDefault().searchRadiusOr0()).append(" of ").append(ship.home()); return ship.withDest(null); }
                    ship = ship.withDest(next);
                    note.next().append("searching toward ").append(next);
                }
            }
        }
        return ship;
    }

    /** What sends her home: shells, fuel or crew below what the mission rules allow, or null. */
    private static String lowOn(Ctx ctx, UnitsCfg.ShipsCfg sc, UnitsCfg.ShipClassCfg cls, Ship ship, boolean docked) {
        var mc = sc.missionsOrDefault();
        List<String> low = new ArrayList<>();
        if (sc.combat() != null && cls.magazineOr0() > 0 && ship.stock().get(ctx.com.index("shell")) < cls.magazineOr0() * mc.shellsBelow()) low.add("shells");
        if (sc.fuel() && cls.tankOr0() > 0) {
            double need = cls.tankOr0() * mc.fuelBelow();
            if (!docked) {
                List<Coord> home = SeaRoutes.path(ctx.snap, ctx.cfg, ship.owner(), ship.at(), ship.home());
                if (home != null) need = Math.max(need, (home.size() - 1) * cls.fuelPerHexOr0() * mc.reserve());
            }
            if (ship.fuel() < need) low.add("fuel");
        }
        if (sc.crews() && ship.crew() < cls.crewOr0()) low.add("crew");
        return low.isEmpty() ? null : String.join(" and ", low);
    }

    /**
     * The next leg of a search: a sea hex within the search radius of home and a leg's hops of her,
     * drawn at random, weighted toward water her country has not seen lately — unseen water most of
     * all. Deliberately unpredictable, so nobody can learn the route; the one mission whose purpose is
     * the fog.
     */
    static Coord pickSearch(Ctx ctx, UnitsCfg.ShipsCfg sc, Ship ship) {
        var mc = sc.missionsOrDefault();
        int radius = mc.searchRadiusOr0(), hops = mc.searchHopsOr0();
        if (radius <= 0 || hops <= 0) return null;
        Map<Coord, Long> seen = new HashMap<>();
        for (SeenSector m : ctx.snap.seenBy(ship.owner())) seen.put(m.at(), m.seenUpdate());
        java.util.SplittableRandom rng = org.hastingtx.empire.engine.update.Rng.stream("search:" + ship.id() + ":" + ctx.snap.updateNumber(), ctx.seed);
        List<Coord> cands = new ArrayList<>(); List<Double> weights = new ArrayList<>(); double total = 0;
        for (Coord c : org.hastingtx.empire.engine.combat.Blockade.within(ctx.snap, ship.at(), hops)) {
            if (c.equals(ship.at()) || ctx.snap.sector(c).terrain() != Terrain.OCEAN) continue;
            if (Hex.distance(ctx.snap, c, ship.home()) > radius) continue;
            if (SeaRoutes.path(ctx.snap, ctx.cfg, ship.owner(), ship.at(), c) == null) continue;
            Long when = seen.get(c);
            double wgt = 1.0 + (when == null ? radius : Math.min(radius, ctx.snap.updateNumber() - when));
            cands.add(c); weights.add(wgt); total += wgt;
        }
        if (cands.isEmpty()) return null;
        double r = rng.nextDouble() * total;
        for (int i = 0; i < cands.size(); i++) { r -= weights.get(i); if (r <= 0) return cands.get(i); }
        return cands.get(cands.size() - 1);
    }

    // ---------------------------------------------------------------------------------- fuel and crews

    /**
     * Top the tank up from the harbour's own stock (issue #65). The fuel is not consumed here — it moves
     * from a sector into a tank, and a tank is counted in conservation exactly like a hold, because fuel
     * sitting in a ship is still fuel. It leaves the world when it is burned, a hex at a time.
     */
    private static Ship refuel(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, int hi, Log note) {
        double room = cls.tankOr0() - ship.fuel();
        if (room < 1) return ship;
        int pet = ctx.com.index(ctx.cfg.units().ships().fuelId());
        double took = fromQuay(ctx, ship, hi, pet, room);
        if (took <= 0) { if (ship.fuel() < cls.fuelPerHexOr0()) note.next().append("no ").append(ctx.com.id(pet)).append(" in the harbour or the warehouse beside it to refuel"); return ship; }
        note.next().append("took on ").append(Ledger.q(took)).append(' ').append(ctx.com.id(pet));
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
    private static Ship refuelAtSea(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, List<Ship> done, Log note) {
        double room = cls.tankOr0() - ship.fuel();
        if (room < 1 || ship.fuel() >= cls.fuelPerHexOr0()) return ship;   // only a ship that needs it
        int pet = ctx.com.index(ctx.cfg.units().ships().fuelId());
        for (int k = 0; k < done.size(); k++) {
            Ship t = done.get(k);
            if (t.owner() != ship.owner() || !t.at().equals(ship.at())) continue;
            if (!"tanker".equals(ctx.cfg.units().ships().shipClass(t.cls()).role())) continue;
            double give = Math.floor(Math.min(room, t.stock().get(pet)));
            if (give < 1) continue;
            done.set(k, t.withStock(t.stock().plus(pet, -give)).tally(Ship.FUEL_GIVEN, give));
            note.next().append("refuelled ").append(Ledger.q(give)).append(' ').append(ctx.com.id(pet)).append(" from ").append(label(t));
            return ship.withFuel(ship.fuel() + give);
        }
        return ship;
    }

    /**
     * A tanker that cannot make another hex drinks from its own cargo (issue #67). It would be absurd for
     * a hull carrying six thousand tons of petrol to sit dead in the water for want of a tankful. Only
     * when it needs it, as at sea, so what it was carrying for someone else mostly arrives.
     */
    private static Ship refuelFromOwnHold(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, Log note) {
        if (!"tanker".equals(cls.role()) && !cls.tender()) return ship;
        double room = cls.tankOr0() - ship.fuel();
        // a tanker only when she cannot make a hex; a tender whenever she is below half, since reaching
        // ships is her whole job and the petrol aboard is meant for exactly this (issue #182)
        if (room < 1 || ship.fuel() >= (cls.tender() ? cls.tankOr0() / 2 : cls.fuelPerHexOr0())) return ship;
        int pet = ctx.com.index(ctx.cfg.units().ships().fuelId());
        double take = Math.floor(Math.min(room, ship.stock().get(pet)));
        if (take < 1) return ship;
        note.next().append("filled her own tank with ").append(Ledger.q(take)).append(' ').append(ctx.com.id(pet)).append(" from the hold");
        return ship.withStock(ship.stock().plus(pet, -take)).withFuel(ship.fuel() + take);
    }

    /**
     * Guns up to what she can bring to bear, shells up to her magazine, from the harbour's own stock
     * (issue #68). Like fuel it is a move, not a purchase: the harbour has to have been sent them.
     */
    private static Ship rearm(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, int hi, Log note) {
        StringBuilder took = new StringBuilder();
        String[] what = {"gun", "shell"};
        double[] want = {cls.gunsOr0(), cls.magazineOr0()};
        for (int k = 0; k < what.length; k++) {
            int c = ctx.com.index(what[k]);
            double room = Math.min(want[k] - ship.stock().get(c), cls.hold() - ship.load());
            if (room < 1) continue;
            double got = fromQuay(ctx, ship, hi, c, room);
            if (got <= 0) continue;
            ship = ship.withStock(ship.stock().plus(c, got));
            took.append(took.isEmpty() ? "" : " and ").append(Ledger.q(got)).append(' ').append(what[k]).append(got == 1 ? "" : "s");
        }
        if (!took.isEmpty()) note.next().append("rearmed with ").append(took);
        return ship;
    }

    /**
     * Up to {@code want} of commodity {@code c} off the quay: the harbour first, then any dockside warehouse
     * of hers beside it (issues #202, #203, #197) — the same sectors load and unload already work. A large
     * fleet was draining its harbour's petrol mid-update while the warehouse next door stood full. Whole
     * units, moved through the ledger. Returns what was taken.
     */
    private static double fromQuay(Ctx ctx, Ship ship, int hi, int c, double want) {
        double got = 0;
        for (int si : dockside(ctx, ctx.sector(hi), ship.owner())) {
            if (want - got < 1) break;
            double have = ctx.sector(si).stock().get(c) + ctx.led().st(si, c);
            if (have < 1) continue;
            got += ctx.led().toShip(si, c, Math.min(want - got, have));
        }
        return got;
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
    private static Ship muster(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, int hi, Log note) {
        double want = cls.crewOr0() - ship.crew();
        if (want < 1) return ship;
        int who = crewCommodity(ctx, cls);
        double got = fromQuay(ctx, ship, hi, who, want);
        if (got <= 0) { note.next().append("no ").append(ctx.com.id(who)).append(" in the harbour or the warehouse beside it to crew her"); return ship; }
        note.next().append("signed on ").append(Ledger.q(got)).append(' ').append(ctx.com.id(who));
        return ship.withCrew(ship.crew() + got);
    }

    // ---------------------------------------------------------------------------------- the water

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
        // only the hexes a leg away, in canonical order (Hex.within is sorted like the sector list), so the
        // draw below sees the same candidates in the same order as a scan of the whole world did
        for (Coord at : Hex.within(ctx.snap, ship.at(), hops)) {
            Sector s = ctx.snap.sector(at);
            if (s.terrain() != Terrain.OCEAN || s.at().equals(ship.at())) continue;
            if (Hex.distance(ctx.snap, s.at(), ship.home()) > fc.radius()) continue;
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

    // ---------------------------------------------------------------------------------- the quay

    /**
     * The sectors a docked ship may work, in a fixed order: its harbour first, then any {@code dockside}
     * sector of the same country in an adjacent hex — the warehouse next door (issue #78). Neighbour
     * order is deterministic, so two ships working the same warehouse contend exactly as they already do
     * over harbour stock, resolved through the ledger in ship-id order.
     */
    static List<Integer> dockside(Ctx ctx, Sector harbor, int owner) {
        List<Integer> out = new ArrayList<>();
        out.add(ctx.idx(harbor.at()));
        for (Coord nb : Hex.neighbours(ctx.snap, harbor.at())) {
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

    /**
     * Load surplus (above each sector's thresholds) from the harbour, then any dockside warehouse, up to
     * the hold. {@code limit}, when given, caps each commodity — what the far end actually wants (issue #67).
     */
    private static Ship load(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, Sector harbor, int hi, List<Integer> wanted, double[] limit, Log note) {
        double room = cls.hold() - ship.load();
        double[] taken = new double[ctx.com.size()];
        StringBuilder took = new StringBuilder();
        for (int sx : dockside(ctx, harbor, ship.owner())) {
            if (room <= 1e-9) break;
            Sector src = ctx.sector(sx);
            StringBuilder here = new StringBuilder();
            for (int c = 0; c < ctx.com.size() && room > 1e-9; c++) {
                if (!wanted.isEmpty() && !wanted.contains(c)) continue;
                if (!carries(ctx, cls, c)) continue;
                double keep = src.hasThreshold(c) ? src.threshold(c) : 0;
                double avail = src.stock().get(c) + ctx.led().st(sx, c) - keep;
                double q = Math.min(room, avail);
                if (limit != null) q = Math.min(q, limit[c] - taken[c]);
                if (q <= 1e-9) continue;
                q = ctx.led().toShip(sx, c, q);              // whole units both sides, or the two disagree (issue #77)
                if (q <= 0) continue;
                room -= q;
                taken[c] += q;
                ship = ship.withStock(ship.stock().plus(c, q));
                here.append(here.isEmpty() ? "" : ", ").append(Ledger.q(q)).append(' ').append(ctx.com.id(c));
            }
            if (here.isEmpty()) continue;
            took.append(took.isEmpty() ? "" : "; ").append(here).append(sx == hi ? "" : " from the warehouse at " + src.at());
            ctx.led().note(sx, label(ship) + " loaded " + here);
        }
        if (!took.isEmpty()) note.next().append("loaded ").append(took).append(" at ").append(harbor.at());
        else note.next().append("nothing to load at ").append(harbor.at());
        return ship;
    }

    /**
     * Unload into the harbour, then into any dockside warehouse that still has room (issue #78).
     * {@code limit}, when given, caps each commodity — what this harbour is short of (issue #67).
     */
    private static Ship unload(Ctx ctx, Ship ship, Sector harbor, int hi, double[] limit, Log note) {
        StringBuilder put = new StringBuilder();
        Stocks st = ship.stock();
        double[] given = new double[ctx.com.size()];
        for (int sx : dockside(ctx, harbor, ship.owner())) {
            Sector dst = ctx.sector(sx);
            StringBuilder here = new StringBuilder();
            for (int c = 0; c < ctx.com.size(); c++) {
                double q = st.get(c);
                if (limit != null) q = Math.min(q, limit[c] - given[c]);
                if (q <= 1e-9) continue;
                double room = ctx.com.isPerson(c)
                        ? Math.max(0, ctx.maxPopulation(dst) - (dst.stock().get(ctx.com.civ) + ctx.led().st(sx, ctx.com.civ) + dst.stock().get(ctx.com.uw) + ctx.led().st(sx, ctx.com.uw)))
                        : Math.max(0, ctx.capacity(dst, c) - (dst.stock().get(c) + ctx.led().st(sx, c)));
                double u = Math.min(q, room);
                if (u <= 1e-9) continue;
                u = ctx.led().fromShip(sx, c, u);
                if (u <= 0) continue;
                st = st.plus(c, -u);
                given[c] += u;
                here.append(here.isEmpty() ? "" : ", ").append(Ledger.q(u)).append(' ').append(ctx.com.id(c));
            }
            if (here.isEmpty()) continue;
            put.append(put.isEmpty() ? "" : "; ").append(here).append(sx == hi ? "" : " into the warehouse at " + dst.at());
            ctx.led().note(sx, label(ship) + " unloaded " + here);
        }
        if (!put.isEmpty()) note.next().append("unloaded ").append(put).append(" at ").append(harbor.at());
        Ship out = ship.withStock(st);
        for (int c = 0; c < given.length; c++) out = out.tally(Ship.DELIVERED + ctx.com.id(c), given[c]);   // the manifest (issue #244)
        return out;
    }
}
