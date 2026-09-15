package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.CaptureCfg;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Taking a held sector by force: from the sea (issue #206, {@code land} on enemy coast) and over land (issue #236,
 * {@code attack} from sectors next to it). Richard 2026-09-14: at war only, man for man until one side is gone, the
 * defender's neighbours fight too, and the winner takes the sector with its people and most of its stock. Resolved
 * now, like {@code fire}, and deterministic from the game seed, who attacks, the sector, the update and the numbers.
 *
 * <p>Each roll kills one soldier: a defender with probability {@code A / (A + D)}, where A and D are the two sides'
 * strength — soldiers × {@link CaptureCfg.AssaultCfg#perMan} of their efficiency, defenders in a fortress × the fort
 * bonus. A fallen defender is drawn from the defending sectors in proportion to their men. Win, and the sector is
 * yours with its designation and people, garrisoned by the survivors, less the capture losses in goods and road and
 * rail. Lose, and every soldier sent is gone.
 */
final class Assault {
    private Assault() {}

    /** How a fight went: who is left on each side, and the world with the defenders' losses taken off. */
    private record Fight(World next, boolean won, double survivors, double defendersAtStart, double defendersLost, double inSector, double fort) {
        String story(String what, Coord at, double attackers) {
            StringBuilder s = new StringBuilder(what).append(" on ").append(at).append(": ").append(q(attackers)).append(" mil against ").append(q(defendersAtStart)).append(" defenders");
            if (defendersAtStart - inSector >= 1) s.append(" (").append(q(inSector)).append(" in the sector, ").append(q(defendersAtStart - inSector)).append(" from next door)");
            if (fort > 1) s.append(", fortified ×").append(q(fort));
            return s.toString();
        }
    }

    /** Refusals common to both ways in; null when the fight may go ahead. */
    private static String refused(GameConfig cfg, World w, Country c, Sector target, String verb) {
        CaptureCfg cap = cfg.capture();
        Country them = w.country(target.owner());
        if (cap == null || cap.assault() == null) return target.at() + " belongs to " + them.name() + ", and this world has no rules for taking sectors by force";
        if (!w.atWar(c.id(), them.id())) return target.at() + " belongs to " + them.name() + "; you may " + verb + " it only at war (declare war first)";
        if (them.inSanctuary() || target.sanctuary()) return them.name() + " is in sanctuary and cannot be touched";
        return null;
    }

    private static Fight fight(GameConfig cfg, Commodities com, World w, Sector target, double attackers, double attStrength, String streamKey) {
        CaptureCfg.AssaultCfg ac = cfg.capture().assault();
        int them = target.owner();
        record Group(Coord at, double men, double strength) {}
        List<Group> defence = new ArrayList<>();
        defence.add(new Group(target.at(), Math.floor(target.stock().get(com.mil)), ac.perMan(target.efficiency()) * fort(cfg, ac, target)));
        if (ac.neighbours()) for (Coord n : Hex.neighbours(w, target.at())) {
            Sector s = w.sector(n);
            if (s.owner() == them && s.stock().get(com.mil) >= 1)
                defence.add(new Group(n, Math.floor(s.stock().get(com.mil)), ac.perMan(s.efficiency()) * fort(cfg, ac, s)));
        }
        double[] men = defence.stream().mapToDouble(Group::men).toArray();
        double defendersAtStart = java.util.Arrays.stream(men).sum();
        long seed = cfg.world() == null ? 0 : cfg.world().seed();
        SplittableRandom rng = Rng.stream(streamKey + ":" + w.updateNumber() + ":" + (long) attackers + ":" + (long) defendersAtStart, seed);
        double att = attackers;
        while (att >= 1) {
            double d = 0, left = 0;
            for (int k = 0; k < men.length; k++) { d += men[k] * defence.get(k).strength(); left += men[k]; }
            if (left < 1) break;
            double a = att * attStrength;
            if (rng.nextDouble() < a / (a + d)) {
                // a defender falls, drawn from the sectors in proportion to the men each still has
                double r = rng.nextDouble() * left;
                int fell = -1;
                for (int k = 0; k < men.length; k++) {
                    if (men[k] < 1) continue;
                    fell = k;
                    if (r < men[k]) break;
                    r -= men[k];
                }
                men[fell] -= 1;
            } else att -= 1;
        }
        World next = w;
        for (int k = 0; k < men.length; k++) {
            Sector s = next.sector(defence.get(k).at());
            next = next.withSector(s.withStock(s.stock().with(com.mil, men[k])));
        }
        return new Fight(next, att >= 1, att, defendersAtStart, defendersAtStart - java.util.Arrays.stream(men).sum(), defence.get(0).men(), fort(cfg, ac, target));
    }

    /** The sector changes hands: survivors garrison it, the capture losses come off, its wiring to the old owner is cut. Returns what was lost. */
    private static String capture(GameConfig cfg, Commodities com, World[] world, int attacker, Coord at, double survivors, double civIn) {
        CaptureCfg cap = cfg.capture();
        Sector s = world[0].sector(at);
        Stocks st = s.stock();
        StringBuilder spoiled = new StringBuilder();
        for (int ci = 0; ci < com.size(); ci++) {
            if (com.isPerson(ci)) continue;
            double lost = Math.floor(st.get(ci) * cap.stockDestroyedFraction());
            if (lost >= 1) { st = st.plus(ci, -lost); spoiled.append(spoiled.isEmpty() ? "" : ", ").append(q(lost)).append(' ').append(com.id(ci)); }
        }
        st = st.with(com.mil, survivors).plus(com.civ, civIn);
        var infra = cfg.infrastructure();
        s = s.withOwner(attacker).withStock(st).withDistCenter(null).withDeliver(DeliverOrders.none(com.size()))
             .withRoadLevel(s.roadLevel() * (1 - infra.road().combatDamageFraction()))
             .withRailLevel(s.railLevel() * (1 - infra.rail().combatDamageFraction()));
        world[0] = world[0].withSector(s);
        return spoiled.toString();
    }

    /** From the sea: everyone aboard an assault ship next to enemy coast (issue #206). */
    static CommandResult run(GameConfig cfg, Commodities com, World w, Country c, Ship ship, Sector target) {
        String no = refused(cfg, w, c, target, "assault");
        if (no != null) return CommandResult.fail(w, no);
        double attackers = Math.floor(ship.stock().get(com.mil));
        if (attackers < 1) return CommandResult.fail(w, "ship #" + ship.id() + " has no military aboard to assault with");

        Fight f = fight(cfg, com, w, target, attackers, cfg.capture().assault().perMan(ship.efficiency()), "assault:" + ship.id() + ">" + target.at());
        String story = f.story("assault", target.at(), attackers);
        if (!f.won()) {
            World next = f.next().withShip(ship.withStock(ship.stock().with(com.mil, 0)));
            return new CommandResult(next, null, 0, story + " — thrown back: all " + q(attackers) + " lost; they lost " + q(f.defendersLost()));
        }
        org.hastingtx.empire.engine.update.Ctx rctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        double civAboard = Math.floor(ship.stock().get(com.civ));
        Sector held = f.next().sector(target.at());
        double stockAfterLoss = held.stock().get(com.civ) + held.stock().get(com.uw);
        double civAshore = Math.min(civAboard, Math.max(0, Math.floor(rctx.maxPopulation(target)) - stockAfterLoss));
        World[] next = {f.next()};
        String spoiled = capture(cfg, com, next, c.id(), target.at(), f.survivors(), civAshore);
        next[0] = next[0].withShip(ship.withStock(ship.stock().with(com.mil, 0).plus(com.civ, -civAshore)));
        return new CommandResult(next[0], null, 0, story + " — taken: " + q(f.survivors()) + " survivors hold it; they lost " + q(f.defendersLost())
                + (civAshore >= 1 ? "; " + q(civAshore) + " civ came ashore behind them" : "")
                + (spoiled.isEmpty() ? "" : "; lost in the fighting: " + spoiled));
    }

    /**
     * Over land (issue #236, #71 slice 1): military from sectors of yours next to an enemy sector. Every party leaves
     * home when the fight starts; each sector sending one must have {@code capture.attack.mobility_cost} mobility and pays
     * it. The attackers fight as one body, at the average worth of the men sent.
     */
    static CommandResult attack(GameConfig cfg, Commodities com, World w, Country c, Command.Attack a) {
        if (c.inSanctuary()) return CommandResult.fail(w, "break sanctuary first");
        if (a.target() == null || !w.inBounds(a.target())) return CommandResult.fail(w, "attack where?");
        Sector target = w.sector(a.target());
        if (!target.terrain().isLand()) return CommandResult.fail(w, a.target() + " is sea; attack ships with fire");
        if (target.owner() == c.id()) return CommandResult.fail(w, a.target() + " is already yours");
        if (!target.owned()) return CommandResult.fail(w, a.target() + " belongs to nobody; explore into it");
        String no = refused(cfg, w, c, target, "attack");
        if (no != null) return CommandResult.fail(w, no);
        if (a.parties() == null || a.parties().isEmpty()) return CommandResult.fail(w, "attack with whom? attack " + a.target() + " N from x,y");
        double mobilityCost = cfg.capture().attackOrDefault().mobilityCostOr0();

        World next = w;
        double attackers = 0, worth = 0;
        java.util.Set<Coord> seen = new java.util.HashSet<>();
        for (Command.Attack.Party p : a.parties()) {
            if (p.from() == null || !w.inBounds(p.from())) return CommandResult.fail(w, "attack from where?");
            if (!seen.add(p.from())) return CommandResult.fail(w, p.from() + " is named twice; give each sector once");
            Sector s = next.sector(p.from());
            if (s.owner() != c.id()) return CommandResult.fail(w, "you do not own " + p.from());
            if (!Hex.neighbours(w, a.target()).contains(p.from())) return CommandResult.fail(w, p.from() + " is not next to " + a.target());
            double men = Math.floor(p.mil());
            if (men < 1) return CommandResult.fail(w, "send at least one soldier from " + p.from());
            if (s.stock().get(com.mil) < men) return CommandResult.fail(w, p.from() + " has " + q(s.stock().get(com.mil)) + " military, not " + q(men));
            if (s.mobility() < mobilityCost) return CommandResult.fail(w, p.from() + " has " + q(s.mobility()) + " mobility; an attack from it takes " + q(mobilityCost));
            next = next.withSector(s.withStock(s.stock().plus(com.mil, -men)).withMobility(s.mobility() - mobilityCost));
            attackers += men;
            worth += men * cfg.capture().assault().perMan(s.efficiency());
        }

        Fight f = fight(cfg, com, next, next.sector(a.target()), attackers, worth / attackers, "attack:" + c.id() + ">" + a.target());
        String story = f.story("attack", a.target(), attackers);
        if (!f.won())
            return new CommandResult(f.next(), null, 0, story + " — beaten off: all " + q(attackers) + " lost; they lost " + q(f.defendersLost()));
        World[] held = {f.next()};
        String spoiled = capture(cfg, com, held, c.id(), a.target(), f.survivors(), 0);
        return new CommandResult(held[0], null, 0, story + " — taken: " + q(f.survivors()) + " survivors hold it; they lost " + q(f.defendersLost())
                + (spoiled.isEmpty() ? "" : "; lost in the fighting: " + spoiled));
    }

    private static double fort(GameConfig cfg, CaptureCfg.AssaultCfg ac, Sector s) {
        return cfg.sectorType(s.designation()).hasFlag("defense_bonus") ? ac.fort() : 1;
    }

    private static String q(double v) { return Ledger.q(v); }
}
