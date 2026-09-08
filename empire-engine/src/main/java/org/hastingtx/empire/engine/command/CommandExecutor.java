package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates and applies one command between updates. The single code path for humans,
 * agents, console and panels. Rejections carry a message the issuer sees; nothing is
 * partially applied.
 */
public final class CommandExecutor {
    private final GameConfig cfg;
    private final Commodities com;

    public CommandExecutor(GameConfig cfg) { this.cfg = cfg; this.com = Commodities.of(cfg); }

    public CommandResult execute(World w, int countryId, Command cmd) {
        if (countryId < 0 || countryId >= w.countries().size()) return CommandResult.fail(w, "no such country");
        Country c = w.country(countryId);
        double cost = cfg.economy().btu().cost(cmd.verb());
        if (c.btu() < cost) return CommandResult.fail(w, "not enough BTUs: need " + cost + ", have " + fmt(c.btu()));

        CommandResult r = switch (cmd) {
            case Command.BreakSanctuary b -> breakSanctuary(w, c);
            case Command.Designate d -> designate(w, c, d);
            case Command.Threshold t -> threshold(w, c, t);
            case Command.Distribute d -> distribute(w, c, d);
            case Command.Move m -> move(w, c, m);
            case Command.Explore e -> explore(w, c, e);
            case Command.BuildRoad br -> buildRoad(w, c, br);
        };
        if (!r.ok()) return r;
        World next = r.world();
        Country nc = next.country(countryId);
        next = next.withCountry(nc.withBtu(nc.btu() - cost));
        return new CommandResult(next, null, cost, r.info());
    }

    private CommandResult breakSanctuary(World w, Country c) {
        if (!c.inSanctuary()) return CommandResult.fail(w, "not in sanctuary");
        World next = w;
        for (Sector s : w.ownedBy(c.id())) next = next.withSector(s.withSanctuary(false));
        return new CommandResult(next.withCountry(c.withSanctuary(false)), null, 0);
    }

    private CommandResult designate(World w, Country c, Command.Designate d) {
        Sector s = owned(w, c, d.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + d.sector());
        if (!cfg.hasSectorType(d.type())) return CommandResult.fail(w, "unknown designation: " + d.type());
        SectorTypeCfg t = cfg.sectorType(d.type());
        if (t.hasFlag("no_designate")) return CommandResult.fail(w, "cannot designate a sector as " + d.type());
        if (t.minTechOr0() > c.levels().tech()) return CommandResult.fail(w, d.type() + " requires tech " + t.minTechOr0());
        if (t.terrainRequired() != null && !t.terrainRequired().contains(s.terrain().id()))
            return CommandResult.fail(w, d.type() + " requires terrain " + t.terrainRequired());
        if (t.hasFlag("coastal_required") && !coastal(w, s)) return CommandResult.fail(w, d.type() + " must be coastal");
        if (s.designation().equals(d.type())) return CommandResult.fail(w, "already " + d.type());
        double eff = redesignatedEfficiency(s.designation(), d.type(), s.efficiency());
        World next = w.withSector(s.withDesignation(d.type(), eff));
        if (t.hasFlag("one_per_country_active")) next = next.withCountry(c.withCapital(s.at()));
        return new CommandResult(next, null, 0);
    }

    /** economy.efficiency.redesignate: exact pair (with * wildcards), then same category, then default. */
    double redesignatedEfficiency(String from, String to, double eff) {
        EconomyCfg.EfficiencyCfg.RedesignateCfg r = cfg.economy().efficiency().redesignate();
        Double keep = null;
        if (r.keepFractionByPair() != null) {
            for (String key : new String[] {from + "->" + to, from + "->*", "*->" + to})
                if (r.keepFractionByPair().containsKey(key)) { keep = r.keepFractionByPair().get(key); break; }
        }
        if (keep == null) {
            String fc = cfg.sectorType(from).category(), tc = cfg.sectorType(to).category();
            keep = fc.equals(tc) && !fc.equals("special") ? r.keepFractionSameCategory() : r.keepFractionDefault();
        }
        return Math.max(r.minEfficiencyAfter(), eff * keep);
    }

    private CommandResult threshold(World w, Country c, Command.Threshold t) {
        Sector s = owned(w, c, t.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + t.sector());
        if (!com.has(t.commodity())) return CommandResult.fail(w, "unknown commodity: " + t.commodity());
        double[] th = s.thresholds().clone();
        th[com.index(t.commodity())] = t.amount() < 0 ? Double.NaN : t.amount();
        return new CommandResult(w.withSector(s.withThresholds(th)), null, 0);
    }

    private CommandResult distribute(World w, Country c, Command.Distribute d) {
        Sector s = owned(w, c, d.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + d.sector());
        if (d.center() != null) {
            Sector ctr = owned(w, c, d.center());
            if (ctr == null) return CommandResult.fail(w, "you do not own the centre " + d.center());
            if (!ctr.terrain().isLand()) return CommandResult.fail(w, "centre must be land");
        }
        return new CommandResult(w.withSector(s.withDistCenter(d.center())), null, 0);
    }

    /**
     * Immediate, as in the original: the goods land now and every sector entered pays its mobility
     * now. If mobility along the route is short, the quantity is capped to what fits; the rest stays
     * at the source. Richard, 2026-09-08. Distribution and rail keep the update-time range-and-hold rule.
     */
    private CommandResult move(World w, Country c, Command.Move m) {
        Sector from = owned(w, c, m.from());
        if (from == null) return CommandResult.fail(w, "you do not own " + m.from());
        Sector to = owned(w, c, m.to());
        if (to == null) return CommandResult.fail(w, "you do not own " + m.to());
        if (!com.has(m.commodity())) return CommandResult.fail(w, "unknown commodity: " + m.commodity());
        if (m.qty() <= 0) return CommandResult.fail(w, "quantity must be positive");
        if (c.inSanctuary()) return CommandResult.fail(w, "break sanctuary first");
        int ci = com.index(m.commodity());
        double have = from.stock().get(ci);
        if (have < m.qty()) return CommandResult.fail(w, "only " + fmt(have) + " " + m.commodity() + " in " + m.from());
        if (m.from().equals(m.to())) return CommandResult.fail(w, "that is where it already is");
        org.hastingtx.empire.engine.update.Ctx ctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        java.util.List<Coord> path = org.hastingtx.empire.engine.update.steps.FlowStep.path(ctx, m.from(), m.to(), c.id(), cfg.distribution());
        if (path == null) return CommandResult.fail(w, "no route through your territory from " + m.from() + " to " + m.to());
        int reach = (int) Math.floor(cfg.economy().mobility().manualMoveMaxSectorsPerUpdate().eval(c.levels().tech()));
        if (path.size() - 1 > reach) return CommandResult.fail(w, m.to() + " is " + (path.size() - 1) + " sectors away; your reach is " + reach);
        double weight = com.weight(ci);
        double moving = m.qty();
        double[] unit = new double[path.size()];
        for (int h = 1; h < path.size(); h++) {
            Sector t = w.sector(path.get(h));
            unit[h] = weight * ctx.moveCostInto(t);
            if (unit[h] > 0) moving = Math.min(moving, t.mobility() / unit[h]);
        }
        moving = Math.floor(moving * 1000) / 1000;
        if (moving <= 0) return CommandResult.fail(w, "no mobility along the route (" + path.get(1) + " has " + fmt(w.sector(path.get(1)).mobility()) + ")");
        World next = w;
        for (int h = 1; h < path.size(); h++) {
            Sector t = next.sector(path.get(h));
            next = next.withSector(t.withMobility(Math.max(0, t.mobility() - moving * unit[h])));
        }
        Sector src = next.sector(m.from()), dst = next.sector(m.to());
        next = next.withSector(src.withStock(src.stock().plus(ci, -moving)));
        next = next.withSector(dst.withStock(dst.stock().plus(ci, moving)));
        String info = moving < m.qty() - 1e-9 ? "moved " + fmt(moving) + " of " + fmt(m.qty()) + " " + m.commodity() + " — mobility along the route ran out; the rest stayed in " + m.from()
                                              : "moved " + fmt(moving) + " " + m.commodity() + " to " + m.to();
        return new CommandResult(next, null, 0, info);
    }

    private CommandResult buildRoad(World w, Country c, Command.BuildRoad r) {
        Sector s = owned(w, c, r.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + r.sector());
        if (!s.terrain().isLand()) return CommandResult.fail(w, "cannot pave the sea");
        if (r.targetLevel() < 0 || r.targetLevel() > 100) return CommandResult.fail(w, "road level is 0..100");
        Double cap = cfg.infrastructure().road().maxLevelByTerrain().get(s.terrain().id());
        if (cap != null && r.targetLevel() > cap) return CommandResult.fail(w, s.terrain().id() + " roads top out at " + fmt(cap));
        return new CommandResult(w.withSector(s.withRoadTarget(r.targetLevel())), null, 0);
    }

    private CommandResult explore(World w, Country c, Command.Explore e) {
        if (c.inSanctuary()) return CommandResult.fail(w, "break sanctuary first");
        Sector from = owned(w, c, e.from());
        if (from == null) return CommandResult.fail(w, "you do not own " + e.from());
        if (!w.inBounds(e.to())) return CommandResult.fail(w, "out of bounds: " + e.to());
        Sector to = w.sector(e.to());
        if (!Hex.neighbours(w, e.from()).contains(e.to())) return CommandResult.fail(w, e.to() + " is not adjacent to " + e.from());
        if (!to.terrain().isLand()) return CommandResult.fail(w, e.to() + " is ocean");
        if (to.owned()) return CommandResult.fail(w, e.to() + " is already owned");
        if (e.civs() < 1) return CommandResult.fail(w, "need at least one civilian");
        if (from.stock().get(com.civ) < e.civs()) return CommandResult.fail(w, "only " + fmt(from.stock().get(com.civ)) + " civilians in " + e.from());
        // GUESS: original charged the source sector's mobility for the walk. Cost = civs × cost into target.
        double mobCost = e.civs() * com.weight(com.civ) * moveCostInto(to);
        if (from.mobility() < mobCost) return CommandResult.fail(w, "need " + fmt(mobCost) + " mobility in " + e.from() + ", have " + fmt(from.mobility()));
        World next = w.withSector(from.withMobility(from.mobility() - mobCost).withStock(from.stock().plus(com.civ, -e.civs())));
        next = next.withSector(to.withOwner(c.id()).withStock(to.stock().plus(com.civ, e.civs())));
        return new CommandResult(next, null, 0);
    }

    private double moveCostInto(Sector s) {
        EconomyCfg.MobilityCfg m = cfg.economy().mobility();
        double base = m.moveCostByTerrain().getOrDefault(s.terrain().id(), Double.POSITIVE_INFINITY);
        return base * m.efficiencyDiscount().eval(s.efficiency()) * cfg.infrastructure().road().mobilityDiscountCurve().eval(s.roadLevel());
    }

    private boolean coastal(World w, Sector s) {
        for (Coord n : Hex.neighbours(w, s.at())) if (!w.sector(n).terrain().isLand()) return true;
        return false;
    }

    private static Sector owned(World w, Country c, Coord at) {
        if (at == null || !w.inBounds(at)) return null;
        Sector s = w.sector(at);
        return s.owner() == c.id() ? s : null;
    }

    private static String fmt(double d) { return String.format(java.util.Locale.ROOT, "%.1f", d); }
}
