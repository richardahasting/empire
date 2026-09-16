package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;

import java.util.*;

/**
 * Land unit orders (issue #247, #71 slice 2), as the original gave them (commands/buil.c build_land, commands/marc.c,
 * commands/load.c lload; Richard 2026-09-15: the original is the default).
 */
final class Army {
    private Army() {}

    private static String q(double v) { return Ledger.q(v); }

    /** KNOWN build_land(): in a headquarters, laid down at LAND_MINEFF for that share of its materials and cost. */
    static CommandResult build(GameConfig cfg, Commodities com, World w, Country c, Command.BuildUnit b) {
        UnitsCfg.LandCfg lc = cfg.units().land();
        if (lc == null) return CommandResult.fail(w, "this world has no land units");
        if (b.sector() == null || !w.inBounds(b.sector())) return CommandResult.fail(w, "build where?");
        Sector s = w.sector(b.sector());
        if (s.owner() != c.id()) return CommandResult.fail(w, "you do not own " + b.sector());
        if (!cfg.sectorType(s.designation()).hasFlag("builds_units")) return CommandResult.fail(w, "land units are built in headquarters; " + b.sector() + " is " + s.designation().replace('_', ' '));
        Double minEff = cfg.economy().efficiency().productionMinEfficiency();
        if (minEff != null && s.efficiency() < minEff) return CommandResult.fail(w, "the headquarters at " + b.sector() + " is " + q(s.efficiency()) + "%; it needs " + q(minEff) + "% to build");
        UnitsCfg.LandClassCfg cls = b.cls() == null ? null : lc.landClass(b.cls());
        if (cls == null) return CommandResult.fail(w, "unknown land unit class: " + b.cls() + " (" + String.join(", ", lc.classes().stream().map(UnitsCfg.LandClassCfg::id).toList()) + ")");
        if (c.levels().tech() < cls.techRequired()) return CommandResult.fail(w, cls.name() + " needs tech " + q(cls.techRequired()) + "; you have " + q(c.levels().tech()));
        double share = lc.startEfficiency() / 100.0;
        Stocks st = s.stock();
        double cash = 0;
        StringBuilder used = new StringBuilder();
        for (var e : cls.build().entrySet()) {
            double need = Math.ceil(e.getValue() * share);
            if (e.getKey().equals("cash")) { cash = e.getValue() * share; continue; }
            int ci = com.index(e.getKey());
            if (st.get(ci) < need) return CommandResult.fail(w, b.sector() + " has " + q(st.get(ci)) + " " + e.getKey() + "; " + article(cls.name()) + " needs " + q(need) + " to lay down");
            st = st.plus(ci, -need);
            used.append(used.isEmpty() ? "" : ", ").append(q(need)).append(' ').append(e.getKey());
        }
        if (c.cash() < cash) return CommandResult.fail(w, article(cls.name()) + " costs $" + q(cash) + "; you have $" + q(c.cash()));
        long id = w.nextUnitId();
        LandUnit u = new LandUnit(id, c.id(), cls.id(), s.at(), lc.startEfficiency(), Stocks.zero(com.size()), 0, c.levels().tech(), w.updateNumber(), "laid down");
        World next = w.withSector(s.withStock(st)).withCountry(c.withCash(c.cash() - cash)).withUnit(u);
        return new CommandResult(next, null, 0, cls.name() + " #" + id + " raised at " + s.at() + " at " + q(lc.startEfficiency()) + "% for " + (used.isEmpty() ? "" : used + " and ") + "$" + q(cash)
                + "; it builds up while it stands in the headquarters. Give it soldiers: lload " + id + " mil " + q(cls.carriesOf("mil")));
    }

    /** KNOWN lnd_mobcost(): sector move cost × path factor × 480 / (spd + techfact(tech, spd)); a supply unit's speed is scaled by efficiency. */
    static double costInto(GameConfig cfg, Ctx ctx, LandUnit u, Sector into) {
        UnitsCfg.LandCfg lc = cfg.units().land();
        UnitsCfg.LandClassCfg cls = lc.landClass(u.cls());
        double spd = cls.speedAt(u.tech());
        if (cls.has("supply")) spd *= u.efficiency() / 100.0;
        double techfact = spd * (50.0 + u.tech()) / (200.0 + u.tech());
        double sector = ctx.moveCostInto(into);
        return sector * lc.pathFactor() * lc.speedNumerator() / Math.max(1e-9, spd + techfact);
    }

    /** March through your own land, as far as the unit's mobility carries it, by the cheapest way (commands/marc.c). */
    static CommandResult march(GameConfig cfg, Commodities com, World w, Country c, Command.March m) {
        if (cfg.units().land() == null) return CommandResult.fail(w, "this world has no land units");
        LandUnit u = w.unit(m.unit());
        if (u == null || u.owner() != c.id()) return CommandResult.fail(w, "no land unit #" + m.unit() + " of yours");
        if (u.aboard()) return CommandResult.fail(w, "unit #" + u.id() + " is aboard ship #" + u.ship() + "; put it ashore first");
        if (m.to() == null || !w.inBounds(m.to())) return CommandResult.fail(w, "march where?");
        if (m.to().equals(u.at())) return CommandResult.fail(w, "unit #" + u.id() + " is already at " + u.at());
        Sector dest = w.sector(m.to());
        if (!dest.terrain().isLand()) return CommandResult.fail(w, m.to() + " is sea; a unit goes by ship");
        if (dest.owner() != c.id()) return CommandResult.fail(w, m.to() + " is not yours; to take it, attack it (attack " + m.to() + " ... unit " + u.id() + ")");
        Ctx ctx = new Ctx(w, cfg, com, 0);
        // cheapest path over your own land (Dijkstra, ties by index for determinism)
        int from = w.index(u.at()), to = w.index(m.to());
        double[] dist = new double[w.sectors().size()];
        int[] prev = new int[dist.length];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        dist[from] = 0;
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        open.add(new double[] {0, from});
        while (!open.isEmpty()) {
            double[] cur = open.poll();
            int i = (int) cur[1];
            if (cur[0] > dist[i]) continue;
            if (i == to) break;
            for (int d = 0; d < 6; d++) {
                int j = ctx.neighbour(i, d);
                if (j < 0) continue;
                Sector s = w.sectors().get(j);
                if (!s.terrain().isLand() || s.owner() != c.id()) continue;
                double nd = dist[i] + costInto(cfg, ctx, u, s);
                if (nd < dist[j]) { dist[j] = nd; prev[j] = i; open.add(new double[] {nd, j}); }
            }
        }
        if (Double.isInfinite(dist[to])) return CommandResult.fail(w, "no way over your own land from " + u.at() + " to " + m.to());
        List<Integer> path = new ArrayList<>();
        for (int i = to; i != from; i = prev[i]) path.add(0, i);
        double mob = u.mobility(), spent = 0;
        int steps = 0;
        for (int j : path) {
            double cost = costInto(cfg, ctx, u, w.sectors().get(j));
            if (mob - spent < cost && steps > 0) break;
            if (mob - spent <= 0) break;
            spent += cost; steps++;
        }
        if (steps == 0) return CommandResult.fail(w, "unit #" + u.id() + " has " + q(u.mobility()) + " mobility; the first hex costs " + q(costInto(cfg, ctx, u, w.sectors().get(path.get(0)))));
        Coord at = w.sectors().get(path.get(steps - 1)).at();
        LandUnit moved = u.withAt(at).withMobility(u.mobility() - spent);
        return new CommandResult(w.withUnit(moved), null, 0, "unit #" + u.id() + " marched " + steps + (steps == 1 ? " hex" : " hexes") + " to " + at
                + (at.equals(m.to()) ? "" : " (" + (path.size() - steps) + " to go; its mobility is spent)") + ", " + q(spent) + " mobility");
    }

    /** KNOWN lload / lunload: a unit takes on, or puts down, what its sector has, up to its capacity for that commodity. */
    static CommandResult load(GameConfig cfg, Commodities com, World w, Country c, Command.LoadUnit l) {
        UnitsCfg.LandCfg lc = cfg.units().land();
        if (lc == null) return CommandResult.fail(w, "this world has no land units");
        LandUnit u = w.unit(l.unit());
        if (u == null || u.owner() != c.id()) return CommandResult.fail(w, "no land unit #" + l.unit() + " of yours");
        if (u.aboard()) return CommandResult.fail(w, "unit #" + u.id() + " is aboard ship #" + u.ship() + "; it loads from a sector, not from a hold");
        if (!com.has(l.commodity())) return CommandResult.fail(w, "unknown commodity: " + l.commodity());
        if (l.qty() <= 0) return CommandResult.fail(w, "quantity must be positive");
        int ci = com.index(l.commodity());
        Sector s = w.sector(u.at());
        if (s.owner() != c.id()) return CommandResult.fail(w, "unit #" + u.id() + " is not in a sector of yours");
        UnitsCfg.LandClassCfg cls = lc.landClass(u.cls());
        if (!l.unload()) {
            double cap = cls.carriesOf(l.commodity());
            if (cap <= 0) return CommandResult.fail(w, article(cls.name()) + " does not carry " + l.commodity());
            double q = Math.floor(Math.min(Math.min(l.qty(), cap - u.stock().get(ci)), s.stock().get(ci)));
            if (q < 1) return CommandResult.fail(w, u.stock().get(ci) >= cap ? "unit #" + u.id() + " carries no more than " + q(cap) + " " + l.commodity() : u.at() + " has no " + l.commodity());
            return new CommandResult(w.withSector(s.withStock(s.stock().plus(ci, -q))).withUnit(u.withStock(u.stock().plus(ci, q))), null, 0,
                    "unit #" + u.id() + " took on " + q(q) + " " + l.commodity() + " (" + q(u.stock().get(ci) + q) + "/" + q(cap) + ")");
        }
        double q = Math.floor(Math.min(l.qty(), u.stock().get(ci)));
        if (q < 1) return CommandResult.fail(w, "unit #" + u.id() + " has no " + l.commodity());
        return new CommandResult(w.withSector(s.withStock(s.stock().plus(ci, q))).withUnit(u.withStock(u.stock().plus(ci, -q))), null, 0,
                "unit #" + u.id() + " put down " + q(q) + " " + l.commodity() + " at " + u.at());
    }

    /**
     * A unit goes aboard a ship in one of your harbours, or ashore from her where she lies (issue #252; KNOWN
     * ship.config nla and the light flag: only a light unit goes to sea). A unit aboard travels with the ship, and an
     * assault ship puts it ashore with her landing party.
     */
    static CommandResult board(GameConfig cfg, Commodities com, World w, Country c, Command.Board b) {
        UnitsCfg.LandCfg lc = cfg.units().land();
        if (lc == null) return CommandResult.fail(w, "this world has no land units");
        LandUnit u = w.unit(b.unit());
        if (u == null || u.owner() != c.id()) return CommandResult.fail(w, "no land unit #" + b.unit() + " of yours");
        if (b.ship() == 0) {
            if (!u.aboard()) return CommandResult.fail(w, "unit #" + u.id() + " is already ashore");
            Ship carrier = w.ship(u.ship());
            Sector s = w.sector(carrier == null ? u.at() : carrier.at());
            if (!s.terrain().isLand()) return CommandResult.fail(w, "she is at sea; bring her to a harbour, or land her party on the coast (land " + (carrier == null ? "SHIP" : carrier.id()) + " x,y)");
            if (s.owner() != c.id()) return CommandResult.fail(w, s.at() + " is not yours");
            return new CommandResult(w.withUnit(u.withShip(0).withAt(s.at())), null, 0, "unit #" + u.id() + " went ashore at " + s.at());
        }
        Ship ship = w.ship(b.ship());
        if (ship == null || ship.owner() != c.id()) return CommandResult.fail(w, "no ship #" + b.ship() + " of yours");
        var sc = cfg.units().ships().shipClass(ship.cls());
        if (sc.landUnitsOr0() <= 0) return CommandResult.fail(w, article(sc.name()) + " carries no land units");
        var cls = lc.landClass(u.cls());
        if (!cls.has("light")) return CommandResult.fail(w, article(cls.name()) + " is too heavy to go aboard");
        if (u.aboard()) return CommandResult.fail(w, "unit #" + u.id() + " is already aboard ship #" + u.ship());
        if (!u.at().equals(ship.at())) return CommandResult.fail(w, "unit #" + u.id() + " is at " + u.at() + " and ship #" + ship.id() + " at " + ship.at());
        if (!w.sector(ship.at()).ownedBy(c.id())) return CommandResult.fail(w, "she must be in one of your harbours to take a unit aboard");
        long aboard = w.units().stream().filter(x -> x.ship() == ship.id()).count();
        if (aboard >= sc.landUnitsOr0()) return CommandResult.fail(w, article(sc.name()) + " carries no more than " + sc.landUnitsOr0() + " land units");
        return new CommandResult(w.withUnit(u.withShip(ship.id())), null, 0, "unit #" + u.id() + " went aboard ship #" + ship.id() + " (" + (aboard + 1) + "/" + sc.landUnitsOr0() + ")");
    }

    private static String article(String name) { return ("aeiou".indexOf(Character.toLowerCase(name.charAt(0))) >= 0 ? "an " : "a ") + name; }
}
