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

            // fit out: a docked hull gains efficiency from the harbour's materials and cash
            if (docked && ship.efficiency() < 100 && here.efficiency() >= sc.harborMinEfficiency()) {
                double points = Math.min(sc.dockPointsPerUpdate(), 100 - ship.efficiency());
                StringBuilder used = new StringBuilder();
                for (var e : sc.dockMaterialsPerPoint().entrySet()) {
                    if (e.getValue() <= 0) continue;
                    if (e.getKey().equals("cash")) points = Math.min(points, Math.max(0, ctx.country(ship.owner()).cash() + ctx.led.cash[ship.owner()]) / e.getValue());
                    else { int c = ctx.com.index(e.getKey()); double avail = here.stock().get(c) + ctx.led.stock[hi][c]; points = Math.min(points, avail / e.getValue()); if (avail < sc.dockPointsPerUpdate() * e.getValue()) ctx.led.shortOf(hi, c, sc.dockPointsPerUpdate() * e.getValue() - avail); }
                }
                if (points > 1e-9) {
                    for (var e : sc.dockMaterialsPerPoint().entrySet()) {
                        if (e.getValue() <= 0) continue;
                        if (e.getKey().equals("cash")) { ctx.led.cash[ship.owner()] -= points * e.getValue(); used.append(used.isEmpty() ? "" : ", ").append('$').append(Ledger.q(points * e.getValue())); }
                        else { ctx.led.consume(hi, ctx.com.index(e.getKey()), points * e.getValue()); used.append(used.isEmpty() ? "" : ", ").append(Ledger.q(points * e.getValue())).append(' ').append(e.getKey()); }
                    }
                    ship = ship.withEfficiency(ship.efficiency() + points);
                    note.append("fitted out to ").append(Ledger.q(ship.efficiency())).append('%').append(used.isEmpty() ? "" : " using " + used);
                    ctx.led.note(hi, label(ship) + " fitted out to " + Ledger.q(ship.efficiency()) + "%" + (used.isEmpty() ? "" : " using " + used));
                }
            }
            double eff = ship.efficiency() / 100.0;

            // fishing: food from the sea hex's fertility into the hold
            if (cls.fishingRateOr0() > 0 && here.terrain() == Terrain.OCEAN && eff > 0) {
                double room = Math.max(0, cls.hold() - ship.load());
                double fish = Math.min(room, cls.fishingRateOr0() * here.resources().fertility() * ctx.etus * sc.fishingFoodPerEtuPerFertilityPoint() * eff);
                if (fish > 1e-9) { ship = ship.withStock(ship.stock().plus(ctx.com.food, fish)); ctx.led.produced[ctx.com.food] += fish; sep(note).append("fished ").append(Ledger.q(fish)).append(" food"); if (room - fish < 1e-9) note.append(" (hold full)"); }
                else if (room <= 1e-9) sep(note).append("hold full, no fishing");
            }
            // luxury: happiness while at sea
            if (cls.happinessOr0() > 0 && here.terrain() == Terrain.OCEAN && eff > 0) {
                double h = cls.happinessOr0() * ctx.etus * eff;
                ctx.led.level[ship.owner()][3] += h;
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
            } else if (docked && sc.autoUnloadInHarbor() && cls.fishingRateOr0() > 0 && ship.load() > 0) {
                ship = unload(ctx, ship, here, hi, note);   // a fishing boat home from the grounds lands its catch
            }
            // sail
            if (ship.dest() != null && !ship.dest().equals(ship.at())) {
                List<Coord> path = SeaRoutes.path(ctx.snap, ctx.cfg, ship.owner(), ship.at(), ship.dest());
                if (path == null) sep(note).append("no sea route to ").append(ship.dest());
                else {
                    int range = sc.range(cls, ship.tech(), ship.efficiency());
                    int hops = Math.min(range, path.size() - 1);
                    if (hops <= 0) sep(note).append("too unfit to sail (").append(Ledger.q(ship.efficiency())).append("%)");
                    else {
                        Coord to = path.get(hops);
                        ship = ship.withAt(to);
                        sep(note).append("sailed ").append(hops).append(hops == 1 ? " hex" : " hexes").append(" to ").append(to);
                        if (to.equals(ship.dest())) { note.append(", arrived"); if (ship.lane() == null) ship = ship.withDest(null); }
                    }
                }
            } else if (ship.dest() != null) { if (ship.lane() == null) ship = ship.withDest(null); }
            // upkeep
            if (cls.upkeepPerUpdate() != null) for (var e : cls.upkeepPerUpdate().entrySet()) if (e.getKey().equals("cash")) ctx.led.cash[ship.owner()] -= e.getValue();
            out.add(ship.withNote(note.isEmpty() ? (docked ? "in harbour" : "holding") : note.toString()));
        }
        ctx.ships.clear(); ctx.ships.addAll(out);
    }

    private static StringBuilder sep(StringBuilder sb) { if (!sb.isEmpty()) sb.append("; "); return sb; }
    static String label(Ship s) { return "ship #" + s.id() + (s.name() == null || s.name().isBlank() ? "" : " " + s.name()); }

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

    /** Load the harbour's surplus (above its thresholds) of the wanted commodities, up to the hold. */
    private static Ship load(Ctx ctx, Ship ship, UnitsCfg.ShipClassCfg cls, Sector harbor, int hi, List<Integer> wanted, StringBuilder note) {
        double room = cls.hold() - ship.load();
        StringBuilder took = new StringBuilder();
        for (int c = 0; c < ctx.com.size() && room > 1e-9; c++) {
            if (!wanted.isEmpty() && !wanted.contains(c)) continue;
            if (!carries(ctx, cls, c)) continue;
            double keep = harbor.hasThreshold(c) ? harbor.threshold(c) : 0;
            double avail = harbor.stock().get(c) + ctx.led.stock[hi][c] - keep;
            double q = Math.min(room, avail);
            if (q <= 1e-9) continue;
            ctx.led.stock[hi][c] -= q; room -= q;
            ship = ship.withStock(ship.stock().plus(c, q));
            took.append(took.isEmpty() ? "" : ", ").append(Ledger.q(q)).append(' ').append(ctx.com.id(c));
        }
        if (!took.isEmpty()) { sep(note).append("loaded ").append(took).append(" at ").append(harbor.at()); ctx.led.note(hi, label(ship) + " loaded " + took); }
        else sep(note).append("nothing to load at ").append(harbor.at());
        return ship;
    }

    /** Unload everything into the harbour, up to its capacity (people up to its population room). */
    private static Ship unload(Ctx ctx, Ship ship, Sector harbor, int hi, StringBuilder note) {
        StringBuilder put = new StringBuilder();
        Stocks st = ship.stock();
        for (int c = 0; c < ctx.com.size(); c++) {
            double q = st.get(c);
            if (q <= 1e-9) continue;
            double room = ctx.com.isPerson(c) ? Math.max(0, ctx.maxPopulation(harbor) - (harbor.stock().get(ctx.com.civ) + ctx.led.stock[hi][ctx.com.civ] + harbor.stock().get(ctx.com.uw) + ctx.led.stock[hi][ctx.com.uw]))
                                              : Math.max(0, ctx.capacity(harbor, c) - (harbor.stock().get(c) + ctx.led.stock[hi][c]));
            double u = Math.min(q, room);
            if (u <= 1e-9) continue;
            ctx.led.stock[hi][c] += u; st = st.plus(c, -u);
            put.append(put.isEmpty() ? "" : ", ").append(Ledger.q(u)).append(' ').append(ctx.com.id(c));
        }
        if (!put.isEmpty()) { sep(note).append("unloaded ").append(put).append(" at ").append(harbor.at()); ctx.led.note(hi, label(ship) + " unloaded " + put); }
        return ship.withStock(st);
    }
}
