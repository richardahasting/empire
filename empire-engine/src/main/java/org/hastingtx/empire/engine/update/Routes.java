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
        double total = 0, moving = qty, weight = ctx.weightLeaving(commodity, src);
        Coord holdsAt = null; int hops = 0;
        for (int h = 1; h < path.size(); h++) {
            Sector t = w.sector(path.get(h));
            double unit = weight * ctx.moveCostInto(t);
            double cost = qty * unit;
            hopCosts.add(cost); total += cost;
            if (holdsAt == null) {
                if (hops >= reach) { holdsAt = path.get(h - 1); continue; }
                double can = unit <= 0 ? moving : Math.min(moving, t.mobility() / unit);
                if (can < moving - 1e-9) { holdsAt = path.get(h - 1); moving = Math.max(0, can); if (can <= 1e-9) { moving = 0; continue; } }
                hops++;
            }
        }
        boolean complete = holdsAt == null;
        double arrives = complete ? qty : moving;       // moving = what squeezes through past the choke; the rest parks
        double held = qty - arrives;
        return new Estimate(true, null, path, hopCosts, total, reach, arrives, held, complete ? null : holdsAt, available, src.mobility());
    }

    public static Estimate explore(World w, GameConfig cfg, int owner, Coord from, Coord to, double civs) {
        Commodities com = Commodities.of(cfg);
        if (!w.inBounds(from) || !w.inBounds(to)) return Estimate.fail("out of bounds");
        Sector src = w.sector(from), dst = w.sector(to);
        if (src.owner() != owner) return Estimate.fail("you do not own " + from);
        if (!Hex.neighbours(w, from).contains(to)) return Estimate.fail(to + " is not adjacent to " + from);
        if (!dst.terrain().isLand()) return Estimate.fail(to + " is ocean");
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
