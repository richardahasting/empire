package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.steps.FlowStep;

import java.util.ArrayList;
import java.util.List;

/**
 * What a move or an explore would cost, before the player commits. Uses the same path
 * finder and the same mobility rule as the update, against the current world, ignoring
 * contention with other flows (which is resolved only at the update).
 */
public final class Routes {
    private Routes() {}

    /**
     * @param path            origin..destination, or the two endpoints for explore; null when no route
     * @param hopCosts        mobility debited from each entered sector on the path (path.size()-1 entries)
     * @param totalMobility   sum of hopCosts for the full quantity
     * @param reach           sectors this cargo may travel per update
     * @param arrivesQty      how much reaches the destination this update, given today's mobility and reach
     * @param holdsAt         where the remainder would park, null if all arrives
     */
    public record Estimate(boolean ok, String error, List<Coord> path, List<Double> hopCosts, double totalMobility,
                           int reach, double arrivesQty, double heldQty, Coord holdsAt, double available, double sourceMobility) {
        static Estimate fail(String msg) { return new Estimate(false, msg, List.of(), List.of(), 0, 0, 0, 0, null, 0, 0); }
    }

    public static Estimate move(World w, GameConfig cfg, int owner, Coord from, Coord to, int commodity, double qty) {
        Commodities com = Commodities.of(cfg);
        if (!w.inBounds(from) || !w.inBounds(to)) return Estimate.fail("out of bounds");
        Sector src = w.sector(from), dst = w.sector(to);
        if (src.owner() != owner) return Estimate.fail("you do not own " + from);
        if (dst.owner() != owner) return Estimate.fail("you do not own " + to);
        if (qty <= 0) return Estimate.fail("quantity must be positive");
        double available = src.stock().get(commodity);
        for (MoveOrder m : w.pendingMoves()) if (m.owner() == owner && m.from().equals(from) && m.commodity() == commodity) available -= m.qty();
        if (available < qty) return new Estimate(false, "only " + fmt(available) + " " + com.id(commodity) + " uncommitted in " + from, List.of(), List.of(), 0, 0, 0, 0, null, available, src.mobility());
        Ctx ctx = new Ctx(w, cfg, com, 0);
        List<Coord> path = FlowStep.path(ctx, from, to, owner, cfg.distribution());
        if (path == null) return new Estimate(false, "no route through your territory from " + from + " to " + to, List.of(), List.of(), 0, 0, 0, 0, null, available, src.mobility());
        int reach = (int) Math.floor(cfg.economy().mobility().manualMoveMaxSectorsPerUpdate().eval(w.country(owner).levels().tech()));
        List<Double> hopCosts = new ArrayList<>();
        double total = 0, weight = ctx.weightLeaving(commodity, src);
        int hopsTotal = path.size() - 1, hopsNow = Math.min(hopsTotal, reach);
        double[] unit = new double[path.size()];
        for (int h = 1; h < path.size(); h++) {
            unit[h] = weight * ctx.moveCostInto(w.sector(path.get(h)));
            double cost = qty * unit[h];
            hopCosts.add(cost); total += cost;
        }
        double arrives, held; Coord holdsAt;
        if (cfg.distribution().sourcePays()) {
            // the sender pays the whole route, so what moves is what it can afford end to end (as the command does);
            // the rest never leaves. Beyond reach, what moves parks at the last sector reached.
            double routeUnit = 0;
            for (int h = 1; h <= hopsNow; h++) routeUnit += unit[h];
            double moving = routeUnit <= 0 ? qty : Math.min(qty, src.mobility() / routeUnit);
            moving = Math.floor(moving * 1000) / 1000;
            boolean short_ = hopsNow < hopsTotal;
            arrives = short_ ? 0 : moving;
            held = qty - arrives;
            holdsAt = short_ ? path.get(hopsNow) : from;
        } else {
            // each entered sector pays its own hop: walk it, checking every hop even after an earlier choke,
            // since what squeezed past one may still choke later
            double moving = qty; holdsAt = null; int hops = 0; boolean stopped = false;
            for (int h = 1; h < path.size(); h++) {
                if (stopped) continue;
                if (hops >= reach) { if (holdsAt == null) holdsAt = path.get(h - 1); stopped = true; continue; }
                double can = unit[h] <= 0 ? moving : Math.min(moving, w.sector(path.get(h)).mobility() / unit[h]);
                if (can < moving - 1e-9) {
                    if (holdsAt == null) holdsAt = path.get(h - 1);
                    moving = Math.max(0, can);
                    if (moving <= 1e-9) { moving = 0; stopped = true; continue; }
                }
                hops++;
            }
            arrives = stopped ? 0 : moving;
            held = qty - arrives;
        }
        boolean complete = held <= 1e-9;
        return new Estimate(true, null, path, hopCosts, total, reach, complete ? qty : arrives, complete ? 0 : held, complete ? null : holdsAt, available, src.mobility());
    }

    /** A rail shipment: route over rail, per-sector capacity, range per update; hopCosts carry each sector's capacity. */
    public static Estimate rail(World w, GameConfig cfg, int owner, Coord from, Coord to, int commodity, double qty) {
        Commodities com = Commodities.of(cfg);
        if (!w.inBounds(from) || !w.inBounds(to)) return Estimate.fail("out of bounds");
        Ctx ctx = new Ctx(w, cfg, com, 0);
        int fi = w.index(from), ti = w.index(to);
        if (w.sectors().get(fi).owner() != owner) return Estimate.fail("you do not own " + from);
        if (w.sectors().get(ti).owner() != owner) return Estimate.fail("you do not own " + to);
        if (!ctx.isDepot(fi)) return Estimate.fail(from + " is not a working depot");
        if (!ctx.isDepot(ti)) return Estimate.fail(to + " is not a working depot");
        double available = w.sectors().get(fi).stock().get(commodity);
        for (var o : w.pendingRail()) if (o.owner() == owner && o.from().equals(from) && o.commodity() == commodity) available -= o.qty();
        if (qty <= 0) return Estimate.fail("quantity must be positive");
        if (available < qty) return new Estimate(false, "only " + fmt(available) + " " + com.id(commodity) + " uncommitted in " + from, List.of(), List.of(), 0, 0, 0, 0, null, available, 0);
        List<Coord> path = ctx.railPath(from, to, owner);
        if (path == null) {
            java.util.Set<Integer> reach = ctx.railReach(from, owner);
            int best = fi, bestD = Integer.MAX_VALUE;
            for (int i : reach) { int d = Hex.distance(w, w.sectors().get(i).at(), to); if (d < bestD) { bestD = d; best = i; } }
            return new Estimate(false, "no rail line from " + from + " to " + to + ": the track ends at " + w.sectors().get(best).at(), List.of(), List.of(), 0, 0, 0, 0, null, available, 0);
        }
        List<Double> caps = new ArrayList<>();
        double minCap = Double.MAX_VALUE;
        for (int h = 1; h < path.size(); h++) { double c = ctx.railCapacity(w.index(path.get(h))); caps.add(c); minCap = Math.min(minCap, c); }
        int range = (int) Math.floor(cfg.infrastructure().rail().maxSectorsPerUpdate().eval(w.country(owner).levels().tech()));
        double effScale = Math.min(w.sectors().get(fi).efficiency(), w.sectors().get(ti).efficiency()) / 100.0;
        double moving = Math.min(qty, minCap) * Math.max(0.01, effScale);
        boolean arrives = path.size() - 1 <= range;
        double cash = cfg.infrastructure().rail().cashPer100UnitsShipped() * moving / 100.0;
        return new Estimate(true, null, path, caps, cash, range, arrives ? moving : 0, arrives ? qty - moving : moving, arrives ? null : path.get(range), available, 0);
    }

    public static Estimate explore(World w, GameConfig cfg, int owner, Coord from, Coord to, double civs) {
        Commodities com = Commodities.of(cfg);
        if (!w.inBounds(from) || !w.inBounds(to)) return Estimate.fail("out of bounds");
        Sector src = w.sector(from), dst = w.sector(to);
        if (src.owner() != owner) return Estimate.fail("you do not own " + from);
        if (!Hex.neighbours(w, from).contains(to)) return Estimate.fail(to + " is not adjacent to " + from);
        if (!dst.terrain().isLand()) return Estimate.fail(to + " is ocean");
        if (dst.sanctuary()) return Estimate.fail(to + " is another country's sanctuary; no one may enter until they break sanctuary");
        if (dst.owned()) return Estimate.fail(to + " is already owned");
        double available = src.stock().get(com.civ);
        Ctx ctx = new Ctx(w, cfg, com, 0);
        double cost = Math.max(1, civs) * ctx.weightLeaving(com.civ, src) * ctx.moveCostInto(dst);
        boolean ok = civs >= 1 && available >= civs && src.mobility() >= cost;
        String err = civs < 1 ? "need at least one civilian" : available < civs ? "only " + fmt(available) + " civilians in " + from
                : src.mobility() < cost ? "need " + fmt(cost) + " mobility in " + from + ", have " + fmt(src.mobility()) : null;
        return new Estimate(ok, err, List.of(from, to), List.of(cost), cost, 1, ok ? civs : 0, ok ? 0 : civs, ok ? null : from, available, src.mobility());
    }

    private static String fmt(double d) { return String.format(java.util.Locale.ROOT, "%.1f", d); }
}
