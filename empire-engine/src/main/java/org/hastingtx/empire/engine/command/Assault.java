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
 * An assault from the sea (issue #206, Richard 2026-09-14): {@code land} aimed at enemy-held coast, at war.
 * The military aboard fight the sector's military and the defender's military next door, man for man
 * until one side is gone, as the original fought it. Resolved now, like {@code fire}, and deterministic
 * from the game seed, the ship, the sector, the update and the numbers engaged.
 *
 * <p>Each roll kills one soldier: a defender with probability {@code A / (A + D)}, where A and D are the
 * two sides' strength — soldiers × {@link CaptureCfg.AssaultCfg#perMan} of their efficiency, defenders in a
 * fortress × the fort bonus. A fallen defender is drawn from the defending sectors in proportion to their
 * men. Win, and the sector is yours with its designation and people, garrisoned by the survivors, less
 * the capture losses in goods and road and rail; the civilians aboard follow them ashore. Lose, and every
 * soldier aboard is gone.
 */
final class Assault {
    private Assault() {}

    static CommandResult run(GameConfig cfg, Commodities com, World w, Country c, Ship ship, Sector target) {
        CaptureCfg cap = cfg.capture();
        CaptureCfg.AssaultCfg ac = cap == null ? null : cap.assault();
        Country them = w.country(target.owner());
        if (ac == null) return CommandResult.fail(w, target.at() + " belongs to " + them.name() + ", and this world has no rules for assault");
        if (!w.atWar(c.id(), them.id())) return CommandResult.fail(w, target.at() + " belongs to " + them.name() + "; you may assault it only at war (declare war first)");
        if (them.inSanctuary()) return CommandResult.fail(w, them.name() + " is in sanctuary and cannot be touched");
        double attackers = Math.floor(ship.stock().get(com.mil));
        if (attackers < 1) return CommandResult.fail(w, "ship #" + ship.id() + " has no military aboard to assault with");

        record Group(Coord at, double men, double strength) {}
        List<Group> defence = new ArrayList<>();
        defence.add(new Group(target.at(), Math.floor(target.stock().get(com.mil)), ac.perMan(target.efficiency()) * fort(cfg, ac, target)));
        if (ac.neighbours()) for (Coord n : Hex.neighbours(w, target.at())) {
            Sector s = w.sector(n);
            if (s.owner() == them.id() && s.stock().get(com.mil) >= 1)
                defence.add(new Group(n, Math.floor(s.stock().get(com.mil)), ac.perMan(s.efficiency()) * fort(cfg, ac, s)));
        }
        double[] men = defence.stream().mapToDouble(Group::men).toArray();
        double defendersAtStart = java.util.Arrays.stream(men).sum();
        double attStrength = ac.perMan(ship.efficiency());
        long seed = cfg.world() == null ? 0 : cfg.world().seed();
        SplittableRandom rng = Rng.stream("assault:" + ship.id() + ">" + target.at() + ":" + w.updateNumber() + ":" + (long) attackers + ":" + (long) defendersAtStart, seed);
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
        double defendersLost = defendersAtStart - java.util.Arrays.stream(men).sum();
        boolean won = att >= 1;

        World next = w;
        for (int k = 0; k < men.length; k++) {
            Sector s = next.sector(defence.get(k).at());
            next = next.withSector(s.withStock(s.stock().with(com.mil, men[k])));
        }
        StringBuilder story = new StringBuilder("assault on ").append(target.at()).append(": ").append(q(attackers)).append(" mil against ")
                .append(q(defendersAtStart)).append(" defenders");
        if (defence.size() > 1) story.append(" (").append(q(defence.get(0).men())).append(" in the sector, ").append(q(defendersAtStart - defence.get(0).men())).append(" from next door)");
        if (fort(cfg, ac, target) > 1) story.append(", fortified ×").append(q(fort(cfg, ac, target)));
        if (!won) {
            next = next.withShip(ship.withStock(ship.stock().with(com.mil, 0)));
            return new CommandResult(next, null, 0, story.append(" — thrown back: all ").append(q(attackers)).append(" lost; they lost ").append(q(defendersLost)).toString());
        }

        // taken
        Sector s = next.sector(target.at());
        Stocks st = s.stock();
        StringBuilder spoiled = new StringBuilder();
        for (int ci = 0; ci < com.size(); ci++) {
            if (com.isPerson(ci)) continue;
            double lost = Math.floor(st.get(ci) * cap.stockDestroyedFraction());
            if (lost >= 1) { st = st.plus(ci, -lost); spoiled.append(spoiled.isEmpty() ? "" : ", ").append(q(lost)).append(' ').append(com.id(ci)); }
        }
        org.hastingtx.empire.engine.update.Ctx rctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        double civAboard = Math.floor(ship.stock().get(com.civ));
        double room = Math.max(0, Math.floor(rctx.maxPopulation(target)) - st.get(com.civ) - st.get(com.uw));
        double civAshore = Math.min(civAboard, room);
        st = st.with(com.mil, att).plus(com.civ, civAshore);
        var infra = cfg.infrastructure();
        s = s.withOwner(c.id()).withStock(st).withDistCenter(null).withDeliver(DeliverOrders.none(com.size()))
             .withRoadLevel(s.roadLevel() * (1 - infra.road().combatDamageFraction()))
             .withRailLevel(s.railLevel() * (1 - infra.rail().combatDamageFraction()));
        next = next.withSector(s).withShip(ship.withStock(ship.stock().with(com.mil, 0).plus(com.civ, -civAshore)));
        story.append(" — taken: ").append(q(att)).append(" survivors hold it; they lost ").append(q(defendersLost))
             .append(civAshore >= 1 ? "; " + q(civAshore) + " civ came ashore behind them" : "")
             .append(spoiled.isEmpty() ? "" : "; lost in the fighting: " + spoiled);
        return new CommandResult(next, null, 0, story.toString());
    }

    private static double fort(GameConfig cfg, CaptureCfg.AssaultCfg ac, Sector s) {
        return cfg.sectorType(s.designation()).hasFlag("defense_bonus") ? ac.fort() : 1;
    }

    private static String q(double v) { return Ledger.q(v); }
}
