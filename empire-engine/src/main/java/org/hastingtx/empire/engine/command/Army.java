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

    /**
     * March through your own land, as far as the unit's mobility carries it, by the cheapest way (commands/marc.c).
     *
     * <p>A <b>spy</b> walks anywhere on land instead (KNOWN lndsub.c: anything else is kidnapped the moment it
     * stands in someone else's sector). Every hex of theirs it enters is a chance of being caught — shot at war,
     * merely spotted at peace — so the reply tells you how far it got and whether it is still alive.
     */
    static CommandResult march(GameConfig cfg, Commodities com, World w, Country c, Command.March m) {
        if (cfg.units().land() == null) return CommandResult.fail(w, "this world has no land units");
        LandUnit u = w.unit(m.unit());
        if (u == null || u.owner() != c.id()) return CommandResult.fail(w, "no land unit #" + m.unit() + " of yours");
        if (u.aboard()) return CommandResult.fail(w, "unit #" + u.id() + " is aboard ship #" + u.ship() + "; put it ashore first");
        if (m.to() == null || !w.inBounds(m.to())) return CommandResult.fail(w, "march where?");
        if (m.to().equals(u.at())) return CommandResult.fail(w, "unit #" + u.id() + " is already at " + u.at());
        Sector dest = w.sector(m.to());
        if (!dest.terrain().isLand()) return CommandResult.fail(w, m.to() + " is sea; a unit goes by ship");
        UnitsCfg.LandClassCfg ucls = cfg.units().land().landClass(u.cls());
        boolean spy = ucls != null && ucls.has("spy") && cfg.units().land().spy() != null;
        if (!spy && dest.owner() != c.id()) return CommandResult.fail(w, m.to() + " is not yours; to take it, attack it (attack " + m.to() + " ... unit " + u.id() + ")");
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
                if (!s.terrain().isLand() || (!spy && s.owner() != c.id())) continue;
                double nd = dist[i] + costInto(cfg, ctx, u, s);
                if (nd < dist[j]) { dist[j] = nd; prev[j] = i; open.add(new double[] {nd, j}); }
            }
        }
        if (Double.isInfinite(dist[to])) return CommandResult.fail(w, "no way over " + (spy ? "land" : "your own land") + " from " + u.at() + " to " + m.to());
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
        // a spy is rolled for in every sector of theirs it walks into, and stops where it is caught (KNOWN lndsub.c)
        if (spy) return Spy.marchThrough(cfg, com, w, c, u, path, steps, m.to());
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

    /**
     * Artillery fire (issue #256; KNOWN subs/landgun.c {@code lnd_fire}, {@code landunitgun}). A unit at
     * {@code LAND_MINFIREEFF} or better, ashore, with guns, shells and men to work them throws
     * {@code (4 + roll(6))} per gun by its efficiency at a sector within {@code techfact(tech, range/2)} hexes.
     * A salvo eats the class's ammunition, and a unit with less than that fires a weaker one with what it has.
     * The sector takes it as {@code sect_damage}: efficiency, roads, rail, mobility and every commodity.
     */
    static CommandResult fire(GameConfig cfg, Commodities com, World w, Country c, Command.UnitFire f) {
        UnitsCfg.LandCfg lc = cfg.units().land();
        if (lc == null || lc.gunnery() == null) return CommandResult.fail(w, "this world has no artillery");
        var g = lc.gunnery();
        LandUnit u = w.unit(f.unit());
        if (u == null || u.owner() != c.id()) return CommandResult.fail(w, "no land unit #" + f.unit() + " of yours");
        if (u.aboard()) return CommandResult.fail(w, "unit #" + u.id() + " is aboard ship #" + u.ship() + "; guns are worked ashore");
        UnitsCfg.LandClassCfg cls = lc.landClass(u.cls());
        if (cls == null || cls.gunsOr0() < 1) return CommandResult.fail(w, article(cls == null ? u.cls() : cls.name()) + " carries no guns");
        if (u.efficiency() < g.minEfficiency()) return CommandResult.fail(w, "unit #" + u.id() + " is at " + q(u.efficiency()) + "%; guns need " + q(g.minEfficiency()) + "%");
        if (u.stock().get(com.mil) < 1) return CommandResult.fail(w, "unit #" + u.id() + " has nobody to work the guns");
        double guns = Math.min(cls.gunsOr0(), Math.floor(u.stock().get(com.index("gun"))));
        if (guns < 1) return CommandResult.fail(w, "unit #" + u.id() + " has no guns aboard (lload " + u.id() + " gun N)");
        double shells = Math.floor(u.stock().get(com.index("shell")));
        if (shells < 1) return CommandResult.fail(w, "unit #" + u.id() + " has no shells (lload " + u.id() + " shell N)");
        if (f.at() == null || !w.inBounds(f.at())) return CommandResult.fail(w, "fire at where?");
        Sector target = w.sector(f.at());
        if (!target.terrain().isLand()) return CommandResult.fail(w, f.at() + " is sea; ships are the navy's business");
        if (target.owner() == c.id()) return CommandResult.fail(w, f.at() + " is yours");
        if (!target.owned()) return CommandResult.fail(w, f.at() + " belongs to nobody");
        String no = Assault.refused(cfg, w, c, target, "fire on");
        if (no != null) return CommandResult.fail(w, no);
        double range = cls.rangeAt(u.tech());
        int dist = Hex.distance(w, u.at(), f.at());
        if (dist > range) return CommandResult.fail(w, f.at() + " is " + dist + " hexes away; " + article(cls.name()) + " reaches " + Ledger.q(Math.floor(range)));

        var r = new org.hastingtx.empire.engine.update.steps.UnrestStep.R(
                org.hastingtx.empire.engine.update.Rng.stream("unit-fire:" + u.id() + ">" + f.at() + ":" + w.updateNumber() + ":" + (long) shells,
                        cfg.world() == null ? 0 : cfg.world().seed()));
        double salvo = 0;
        for (int i = 0; i < (int) guns; i++) salvo += g.damageBase() + r.roll(g.damageRoll());
        salvo *= u.efficiency() / 100.0;
        double ammo = cls.ammoOr1();
        if (shells < ammo) { salvo *= shells / ammo; ammo = shells; }
        int dam = (int) salvo;
        LandUnit fired = u.withStock(u.stock().plus(com.index("shell"), -ammo));
        World next = w.withSector(Spy.damage(cfg, com, r, target, dam)).withUnit(fired);
        return new CommandResult(next, null, 0, "unit #" + u.id() + " fired " + (int) guns + (guns == 1 ? " gun" : " guns") + " at " + f.at()
                + ": " + dam + "% of everything " + w.country(target.owner()).name() + " had there, " + Ledger.q(shells - ammo) + " shells left");
    }

    private static String article(String name) { return ("aeiou".indexOf(Character.toLowerCase(name.charAt(0))) >= 0 ? "an " : "a ") + name; }
}
