package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.steps.UnrestStep;

/**
 * Mines (issue #71), as Wolfpack Empire laid and struck them: KNOWN include/damage.h, subs/shpsub.c (shp_sweep,
 * shp_check_one_mines, shp_hit_mine), subs/lndsub.c (lnd_check_one_mines, lnd_hit_mine), commands/mine.c and attsub.c
 * (get_mine_dsupport). One count a sector — sea mines on the sea, land mines on land — and nobody owns them: a ship
 * strikes anyone's; a unit strikes the land mines of a country it is not.
 */
public final class Mines {
    private Mines() {}

    /** One sea hex crossed: what a sweeper cleared, whether she struck one, and what it did to her. */
    public record SeaHex(int swept, boolean struck, double damage) {}

    /**
     * A ship enters a sea hex with {@code mines} in it (KNOWN shp_nav_gauntlet): a sweeper sweeps first — {@code sweep_tries}
     * at {@code sweep_chance} — and then tests her luck once herself; every ship then strikes one with chance
     * N/(N + {@code sea_hit_add}). A strike costs 21 + roll(21), half that for a sweeper, divided by 1 + armour/100.
     */
    public static SeaHex crossSea(UnitsCfg.MinesCfg mc, UnitsCfg.ShipClassCfg cls, UnrestStep.R r, int mines) {
        int swept = 0;
        boolean struck = false;
        if (cls.sweepsOr0()) {
            for (int i = 0; i < mc.sweepTries() && mines - swept > 0; i++) if (r.chance(mc.sweepChance())) swept++;
            struck = strikes(r, mines - swept, mc.seaHitAdd());
        }
        if (!struck) struck = strikes(r, mines - swept, mc.seaHitAdd());
        double damage = 0;
        if (struck) {
            damage = mc.seaDamageBase() + r.roll(mc.seaDamageRoll());
            if (cls.sweepsOr0()) damage /= 2;
            damage /= 1 + cls.armorOr0() / 100.0;
        }
        return new SeaHex(swept, struck, Math.round(damage));
    }

    /** A sail by hand through mined water: the world with the mines swept and struck, the ship as it left her, how far she got. */
    public record Passage(World world, Ship ship, int hops, String story, boolean sinks) {}

    /** The gauntlet a ship sailed by hand runs, hex by hex along {@code path} (the update's sailing runs the same in ShipStep). */
    static Passage sail(GameConfig cfg, Commodities com, World w, Ship ship, java.util.List<Coord> path, int hops) {
        UnitsCfg.MinesCfg mc = cfg.units().mines();
        var sc = cfg.units().ships();
        if (mc == null || sc == null) return new Passage(w, ship, hops, "", false);
        UnitsCfg.ShipClassCfg cls = sc.shipClass(ship.cls());
        UnrestStep.R r = new UnrestStep.R(org.hastingtx.empire.engine.update.Rng.stream("mines:" + ship.id() + ":" + w.updateNumber() + ":hand:" + ship.at(), cfg.world() == null ? 0 : cfg.world().seed()));
        int shell = com.index("shell");
        StringBuilder story = new StringBuilder();
        for (int k = 1; k <= hops; k++) {
            Sector sea = w.sector(path.get(k));
            if (sea.isLand() || sea.mines() <= 0) continue;
            SeaHex hex = crossSea(mc, cls, r, sea.mines());
            int left = sea.mines() - hex.swept();
            if (hex.swept() > 0) {
                double back = shellsBack(cls, ship, shell, hex.swept());
                ship = ship.withStock(ship.stock().plus(shell, back));
                story.append(story.isEmpty() ? "" : "; ").append("swept ").append(hex.swept()).append(hex.swept() == 1 ? " mine" : " mines").append(" at ").append(path.get(k));
            }
            if (hex.struck()) {
                left--;
                ship = ship.withEfficiency(ship.efficiency() - hex.damage());
                story.append(story.isEmpty() ? "" : "; ").append("struck a mine at ").append(path.get(k)).append(": ").append(q(hex.damage())).append("% of her hull");
                w = w.withSector(sea.withMines(left));
                boolean sinks = sc.combat() != null && ship.efficiency() <= sc.combat().sinkAt();
                return new Passage(w, ship, k, story.toString(), sinks);
            }
            w = w.withSector(sea.withMines(left));
        }
        return new Passage(w, ship, hops, story.toString(), false);
    }

    /** KNOWN DMINE_HITCHANCE / DMINE_LHITCHANCE: N/(N + add). */
    public static boolean strikes(UnrestStep.R r, int mines, int add) {
        return mines > 0 && r.chance((double) mines / (mines + add));
    }

    /** A swept mine is a shell back aboard, while the magazine has room (KNOWN shp_sweep). */
    public static double shellsBack(UnitsCfg.ShipClassCfg cls, Ship s, int shell, int swept) {
        return Math.max(0, Math.min(swept, Math.floor(cls.magazineOr0() - s.stock().get(shell))));
    }

    /**
     * KNOWN mine.c mine: a minelayer at sea — exactly sea, not a harbour — lays up to {@code n} mines from her shells, a
     * shell a mine, wherever she is; her standing mission ends. Mines know no owner.
     */
    static CommandResult lay(GameConfig cfg, Commodities com, World w, Country c, Command.Lay l) {
        UnitsCfg.MinesCfg mc = cfg.units().mines();
        var sc = cfg.units().ships();
        if (mc == null || sc == null) return CommandResult.fail(w, "these rules have no mines");
        Ship s = w.ship(l.ship());
        if (s == null || s.owner() != c.id()) return CommandResult.fail(w, "no ship #" + l.ship() + " of yours");
        UnitsCfg.ShipClassCfg cls = sc.shipClass(s.cls());
        if (!cls.laysMinesOr0()) return CommandResult.fail(w, "a " + cls.name() + " does not lay mines; destroyers, submarines and minesweepers do");
        Sector here = w.sector(s.at());
        if (here.isLand()) return CommandResult.fail(w, "ship #" + s.id() + " is in harbour; mines are laid at sea");
        int shell = com.index("shell");
        if (!(l.n() >= 1) || l.n() != Math.rint(l.n())) return CommandResult.fail(w, "lay a whole number of mines, at least 1");
        double n = Math.min(l.n(), Math.floor(s.stock().get(shell)));
        if (n < 1) return CommandResult.fail(w, "ship #" + s.id() + " has no shells to make mines of");
        World next = w.withShip(s.withStock(s.stock().plus(shell, -n)).withMission(null, null).withNote("laid " + q(n) + " mines at " + s.at()))
                .withSector(here.withMines(here.mines() + (int) n));
        return new CommandResult(next, null, 0, "ship #" + s.id() + " laid " + q(n) + " mines at " + s.at() + (n < l.n() ? " (all the shells she had)" : "")
                + "; they know no allegiance — your ships strike them too");
    }

    /**
     * KNOWN mine.c landmine: an engineer, ashore, with mobility, in a sector of yours lays up to {@code n} land mines —
     * as many as its mobility, a shell (its own, then the sector's) and a point of mobility each.
     */
    static CommandResult landmine(GameConfig cfg, Commodities com, World w, Country c, Command.LandMine m) {
        UnitsCfg.MinesCfg mc = cfg.units().mines();
        var land = cfg.units().land();
        if (mc == null || land == null) return CommandResult.fail(w, "these rules have no mines");
        LandUnit u = w.unit(m.unit());
        if (u == null || u.owner() != c.id()) return CommandResult.fail(w, "no unit #" + m.unit() + " of yours");
        var cls = land.landClass(u.cls());
        if (cls == null || !cls.has("engineer")) return CommandResult.fail(w, "only engineers lay land mines");
        if (u.aboard()) return CommandResult.fail(w, "unit #" + u.id() + " is aboard ship #" + u.ship());
        Sector here = w.sector(u.at());
        if (!here.isLand() || here.owner() != c.id()) return CommandResult.fail(w, "land mines go in land of yours; " + u.at() + " is not");
        if (u.mobility() < 1) return CommandResult.fail(w, "unit #" + u.id() + " has no mobility left");
        if (!(m.n() >= 1) || m.n() != Math.rint(m.n())) return CommandResult.fail(w, "lay a whole number of mines, at least 1");
        int shell = com.index("shell");
        double have = Math.floor(u.stock().get(shell)) + Math.floor(here.stock().get(shell));
        double n = Math.min(Math.min(m.n(), Math.floor(u.mobility())), have);
        if (n < 1) return CommandResult.fail(w, "no shells with unit #" + u.id() + " or in " + u.at() + " to make mines of");
        double fromUnit = Math.min(n, Math.floor(u.stock().get(shell))), fromSector = n - fromUnit;
        World next = w.withUnit(u.withStock(u.stock().plus(shell, -fromUnit)).withMobility(u.mobility() - n).withNote("laid " + q(n) + " land mines"))
                .withSector(here.withStock(here.stock().plus(shell, -fromSector)).withMines(here.mines() + (int) n));
        return new CommandResult(next, null, 0, "unit #" + u.id() + " laid " + q(n) + " land mines in " + u.at() + " (" + q(next.sector(u.at()).mines()) + " there now)"
                + "; your own units walk through them, an enemy's strike them, and they strengthen the sector against attack");
    }

    /** KNOWN get_mine_dsupport: a defender's land mines add {@code defence_per_mine} each, at most {@code defence_mine_cap} counted, halved against engineers. */
    public static double defence(GameConfig cfg, Sector target, int attacker, boolean engineers) {
        UnitsCfg.MinesCfg mc = cfg.units().mines();
        if (mc == null || !target.isLand() || target.mines() <= 0 || target.mineOwner() == attacker) return 0;
        double mines = Math.min(target.mines(), mc.defenceMineCap());
        if (engineers) mines = Math.round(mines / 2);
        return mines * mc.defencePerMine();
    }

    private static String q(double v) { return Ledger.q(v); }
}
