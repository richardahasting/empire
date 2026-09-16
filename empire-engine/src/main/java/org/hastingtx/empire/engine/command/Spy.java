package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.steps.UnrestStep;

import java.util.List;

/**
 * Spies behind enemy lines (issue #254, #71 slice 2c).
 *
 * <p>The original's spy (flag {@code L_SPY}) is the one unit that may stand in someone else's sector: anything else
 * is kidnapped there (subs/lndsub.c). It guides itself without soldiers, adds nothing to a defence, and every sector
 * of theirs it walks into is a chance of being caught — {@code LND_SPY_DETECT_CHANCE(eff) = (110 − eff)/100}, one
 * chance in ten at full efficiency. At war it is shot; at peace it is only spotted.
 *
 * <p><b>sabotage</b> is the original's (commands/sabo.c, subs/landgun.c {@code lnd_sabo}): one shell, a blast by the
 * spy's efficiency, and the sector's own shells and petrol going up with it. <b>incite</b> is NEW (Richard
 * 2026-09-15): the spy who goes in to make trouble raises the sector's disloyalty and turns some of its people into
 * guerrillas fighting for whoever held it before (#72). Both risk the spy the same way.
 */
final class Spy {
    private Spy() {}

    private static String q(double v) { return org.hastingtx.empire.engine.update.Ledger.q(v); }

    /** Shared with {@link Army}: the rest of a spy's march, hex by hex, rolling for it in every sector of theirs. */
    static CommandResult marchThrough(GameConfig cfg, Commodities com, World w, Country c, LandUnit u,
                                      List<Integer> path, int steps, Coord to) {
        var spy = cfg.units().land().spy();
        var ctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        UnrestStep.R r = rng(cfg, w, "spy-march:" + u.id() + ":" + u.at() + ">" + to);
        double mobility = u.mobility();
        Coord at = u.at();
        int walked = 0;
        for (int k = 0; k < steps; k++) {
            Sector s = w.sectors().get(path.get(k));
            mobility -= Army.costInto(cfg, ctx, u, s);
            at = s.at();
            walked++;
            if (s.owner() == c.id() || !s.owned()) continue;
            if (!r.chance(spy.detectChance(u.efficiency()))) continue;
            String them = w.country(s.owner()).name();
            if (w.atWar(c.id(), s.owner())) {
                return new CommandResult(w.withoutUnit(u.id()), null, 0,
                        "unit #" + u.id() + " was shot in " + at + " after " + walked + (walked == 1 ? " hex" : " hexes") + ": " + them + " is at war with you");
            }
            return new CommandResult(w.withUnit(u.withAt(at).withMobility(Math.max(0, mobility))), null, 0,
                    "unit #" + u.id() + " was spotted in " + at + " by " + them + " — at peace they only watched, but they know it is there");
        }
        LandUnit moved = u.withAt(at).withMobility(Math.max(0, mobility));
        return new CommandResult(w.withUnit(moved), null, 0, "unit #" + u.id() + " slipped " + walked + (walked == 1 ? " hex" : " hexes") + " to " + at
                + (at.equals(to) ? "" : " (" + (path.size() - steps) + " to go; its mobility is spent)") + ", " + q(u.mobility() - mobility) + " mobility");
    }

    /** KNOWN sabo.c: a shell in the right place, and whatever the sector was keeping goes up with it. */
    static CommandResult sabotage(GameConfig cfg, Commodities com, World w, Country c, Command.Sabotage cmd) {
        Checked ck = check(cfg, com, w, c, cmd.unit(), "sabotage");
        if (ck.fail() != null) return ck.fail();
        LandUnit u = ck.unit();
        Sector s = ck.sector();
        var spy = cfg.units().land().spy();
        var sab = spy.sabotage();
        int shell = com.index("shell");
        if (u.stock().get(shell) < sab.shells()) return CommandResult.fail(w, "unit #" + u.id() + " has no shells to do it with");
        UnrestStep.R r = rng(cfg, w, "sabotage:" + u.id() + ":" + s.at());
        String them = w.country(s.owner()).name();
        if (r.chance(spy.detectChance(u.efficiency()))) {
            return new CommandResult(w.withoutUnit(u.id()), null, 0,
                    "unit #" + u.id() + " was caught laying the charge in " + s.at() + " and shot by " + them);
        }
        u = u.withStock(u.stock().plus(shell, -sab.shells()));
        int dam = (int) fortgun(r, u.efficiency() * sab.fortgunEfficiencyMultiple(), sab.fortgunGuns());
        double theirShells = s.stock().get(shell), theirPetrol = s.stock().get(com.index("pet"));
        if (theirShells > sab.shellsAbove()) dam += (int) seagun(r, u.efficiency(), r.roll0((int) (theirShells / sab.shellsDivisor())));
        if (theirPetrol > sab.petrolAbove()) dam += (int) seagun(r, u.efficiency(), r.roll0((int) (theirPetrol / sab.petrolDivisor())));
        World next = w.withSector(damage(cfg, com, r, s, dam)).withUnit(u);
        String out = "the charge went off in " + s.at() + ": " + dam + "% of everything " + them + " had there";
        // KNOWN sabo.c: the same roll again — a spy standing too close to his own work
        if (r.chance(spy.detectChance(u.efficiency()))) return new CommandResult(next.withoutUnit(u.id()), null, 0, out + "; unit #" + u.id() + " died in the explosion");
        return new CommandResult(next, null, 0, out + "; unit #" + u.id() + " walked away, " + q(u.stock().get(shell)) + " shells left");
    }

    /**
     * NEW (Richard 2026-09-15): the spy who makes trouble rather than noise. The sector grows disloyal, and once it
     * is disloyal enough some of its people take up arms for whoever held it before — the unrest of #72, but started
     * by a country rather than by hunger or conquest.
     */
    static CommandResult incite(GameConfig cfg, Commodities com, World w, Country c, Command.Incite cmd) {
        Checked ck = check(cfg, com, w, c, cmd.unit(), "incite");
        if (ck.fail() != null) return ck.fail();
        LandUnit u = ck.unit();
        Sector s = ck.sector();
        var unrest = cfg.economy().unrest();
        if (unrest == null) return CommandResult.fail(w, "this world has no unrest to stir up");
        var spy = cfg.units().land().spy();
        var inc = spy.incite();
        UnrestStep.R r = rng(cfg, w, "incite:" + u.id() + ":" + s.at() + ":" + w.updateNumber());
        String them = w.country(s.owner()).name();
        if (r.chance(spy.detectChance(u.efficiency()))) {
            return new CommandResult(w.withoutUnit(u.id()), null, 0,
                    "unit #" + u.id() + " was caught talking to the wrong people in " + s.at() + " and shot by " + them);
        }
        int loyalty = Math.min(127, s.loyalty() + inc.loyalty());   // KNOWN sct_loyal tops out at 127
        // past the line where people stop working for them, some take up arms for whoever held it before
        int civ = (int) s.stock().get(com.civ);
        int che = s.che(), target = s.cheTarget();
        String rising = "";
        Stocks st = s.stock();
        if (loyalty > unrest.populace().disloyalAbove() && civ >= inc.minCivilians()) {
            int rise = (int) Math.floor(civ * inc.cheShare());
            if (rise + che > unrest.cheMax()) rise = unrest.cheMax() - che;
            if (rise > 0) {
                st = st.plus(com.civ, -rise);
                che += rise;
                target = s.owner();      // KNOWN revolt.c: the guerrillas' target is whoever holds the sector
                rising = "; " + rise + " of them took up arms";
            }
        }
        Sector stirred = s.withStock(st).withUnrest(loyalty, s.work(), s.oldOwner(), che, che > 0 ? target : Sector.NOBODY);
        return new CommandResult(w.withSector(stirred), null, 0,
                "unit #" + u.id() + " worked on " + s.at() + ": " + them + " is trusted less there (disloyalty " + loyalty + ")" + rising);
    }

    // ---- shared ----

    private record Checked(CommandResult fail, LandUnit unit, Sector sector) {}

    /** Every way a spy's errand is refused before any dice are thrown. */
    private static Checked check(GameConfig cfg, Commodities com, World w, Country c, long id, String what) {
        UnitsCfg.LandCfg lc = cfg.units().land();
        if (lc == null || lc.spy() == null) return new Checked(CommandResult.fail(w, "this world has no spies"), null, null);
        LandUnit u = w.unit(id);
        if (u == null || u.owner() != c.id()) return new Checked(CommandResult.fail(w, "no land unit #" + id + " of yours"), null, null);
        if (u.aboard()) return new Checked(CommandResult.fail(w, "unit #" + u.id() + " is aboard ship #" + u.ship()), null, null);
        UnitsCfg.LandClassCfg cls = lc.landClass(u.cls());
        if (cls == null || !cls.has("spy")) return new Checked(CommandResult.fail(w, "unit #" + u.id() + " is not a spy"), null, null);
        Sector s = w.sector(u.at());
        if (s.owner() == c.id()) return new Checked(CommandResult.fail(w, "nothing to " + what + " in your own sector; march it into theirs first"), null, null);
        if (!s.owned()) return new Checked(CommandResult.fail(w, u.at() + " belongs to nobody"), null, null);
        return new Checked(null, u, s);
    }

    private static UnrestStep.R rng(GameConfig cfg, World w, String key) {
        return new UnrestStep.R(Rng.stream(key + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));
    }

    /** KNOWN subs/landgun.c fortgun(): {@code (roll(30) + 19) × min(guns,7)/7 × effic/100}. */
    private static double fortgun(UnrestStep.R r, double efficiency, int guns) {
        double g = Math.min(guns, 7);
        return (r.roll(30) + 19.0) * (g / 7.0) * efficiency / 100.0;
    }

    /** KNOWN subs/landgun.c seagun(): {@code sum of (9 + roll(6)) over guns, × effic/100}. */
    private static double seagun(UnrestStep.R r, double efficiency, int guns) {
        double d = 0;
        for (int i = 0; i < guns; i++) d += 9.0 + r.roll(6);
        return d * efficiency / 100.0;
    }

    /**
     * KNOWN subs/sectdamage.c sect_damage(), applied to a sector outside an update: efficiency, road, rail,
     * mobility and every item lose {@code dam} percent. The update's own copy works through the ledger instead.
     */
    private static Sector damage(GameConfig cfg, Commodities com, UnrestStep.R r, Sector s, int dam) {
        if (dam <= 0) return s;
        dam = Math.min(100, dam);
        Stocks st = s.stock();
        for (int ci = 0; ci < com.size(); ci++) {
            int lost = r.roundavg(st.get(ci) * dam / 100.0);
            if (lost > 0) st = st.plus(ci, -Math.min(lost, st.get(ci)));
        }
        return s.withStock(st)
                .withEfficiency(Math.max(0, s.efficiency() - r.roundavg(s.efficiency() * dam / 100.0)))
                .withRoadLevel(Math.max(0, s.roadLevel() - r.roundavg(s.roadLevel() * dam / 100.0)))
                .withRailLevel(Math.max(0, s.railLevel() - r.roundavg(s.railLevel() * dam / 100.0)))
                .withMobility(Math.max(0, s.mobility() - r.roundavg(s.mobility() * dam / 100.0)));
    }
}
