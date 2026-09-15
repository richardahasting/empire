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
        // KNOWN (subs/takeover.c, issue #72): the people of a sector that was theirs may take up arms at once, by how
        // loyal they were; a sector taken from another country starts disloyal, one taken back is yours again
        var unrest = cfg.economy().unrest();
        int loser = s.owner(), loyalty = 0, old = attacker, che = s.che(), cheTarget = s.cheTarget();
        String partisans = "";
        if (unrest != null) {
            var uc = unrest.capture();
            int civ = (int) st.get(com.civ);
            var rng = new org.hastingtx.empire.engine.update.steps.UnrestStep.R(Rng.stream("takeover:" + at + ":" + world[0].updateNumber() + ":" + attacker, cfg.world() == null ? 0 : cfg.world().seed()));
            int n = (uc.pivot() - s.loyalty()) + (rng.roll(uc.roll()) - uc.offset());
            if (n > 0 && s.owner() == s.oldOwner()) {
                int rise = civ * n / uc.perCiv() + uc.base();
                if (rise * 2 > civ) rise = civ / 2;
                rise = (int) (rise / unrest.hapFact(world[0].country(attacker).levels().happiness(), world[0].country(loser).levels().happiness()));
                if (rise + che > unrest.cheMax()) rise = unrest.cheMax() - che;
                if (rise > 0) { st = st.plus(com.civ, -rise); che += rise; partisans = rise + " civilians took up arms"; }
            }
            if (attacker != s.oldOwner()) cheTarget = che > 0 ? attacker : cheTarget;
            if (s.oldOwner() == attacker || st.get(com.civ) < 1) { loyalty = 0; old = attacker; }
            else { loyalty = uc.loyalty(); old = s.oldOwner(); }
        }
        st = st.with(com.mil, survivors).plus(com.civ, civIn);
        var infra = cfg.infrastructure();
        // KNOWN (subs/takeover.c): the distribution info is wiped and a taken sector's mobility is 0
        s = s.withOwner(attacker).withStock(st).withDistCenter(null).withDeliver(DeliverOrders.none(com.size())).withMobility(0)
             .withRoadLevel(s.roadLevel() * (1 - infra.road().combatDamageFraction()))
             .withRailLevel(s.railLevel() * (1 - infra.rail().combatDamageFraction()));
        if (unrest != null) s = s.withUnrest(loyalty, s.work(), old == attacker ? Sector.NOBODY : old, che, che > 0 ? cheTarget : Sector.NOBODY);
        world[0] = world[0].withSector(s);
        return partisans.isEmpty() ? spoiled.toString() : (spoiled.isEmpty() ? "" : spoiled + "; ") + partisans + " against you";
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
     * Over land (issue #236, #71 slice 1): military from sectors of yours next to an enemy sector, fighting as one body
     * at the average worth of the men sent. Mobility as the original (subs/attsub.c, Richard 2026-09-15: the original is
     * the default): a sector pays soldiers × the cost of moving one into the target, so its mobility caps how many it
     * can send; its casualties cost it a further share of its mobility, at most 20. Survivors move in.
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
        org.hastingtx.empire.engine.update.Ctx mctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        double perMan = mctx.moveCostInto(target);
        if (!Double.isFinite(perMan)) return CommandResult.fail(w, "no soldier can move into " + a.target());
        double lossCap = cfg.capture().attackOrDefault().casualtyMobilityCapOr0();
        record Sent(Coord from, double men, double homeMil, double homeMob) {}
        List<Sent> sent = new ArrayList<>();

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
            double cost = men * mctx.weightLeaving(com.mil, s) * perMan;
            if (s.mobility() < cost) return CommandResult.fail(w, p.from() + " has " + q(s.mobility()) + " mobility, which can carry "
                    + q(Math.floor(s.mobility() / (mctx.weightLeaving(com.mil, s) * perMan))) + " soldiers into " + a.target() + ", not " + q(men));
            sent.add(new Sent(p.from(), men, s.stock().get(com.mil), s.mobility()));
            next = next.withSector(s.withStock(s.stock().plus(com.mil, -men)).withMobility(s.mobility() - Math.max(1, cost)));
            attackers += men;
            worth += men * cfg.capture().assault().perMan(s.efficiency());
        }

        Fight f = fight(cfg, com, next, next.sector(a.target()), attackers, worth / attackers, "attack:" + c.id() + ">" + a.target());
        // the dead cost their sectors mobility, in proportion to the share of the garrison lost, at most the cap each (attsub.c)
        double dead = attackers - f.survivors();
        World after = f.next();
        for (Sent p : sent) {
            double lost = dead * p.men() / attackers;
            if (lost <= 0 || p.homeMil() <= 0) continue;
            Sector s = after.sector(p.from());
            double extra = Math.min(lossCap, p.homeMob() * Math.min(1, lost / p.homeMil()));
            after = after.withSector(s.withMobility(Math.max(0, s.mobility() - extra)));
        }
        f = new Fight(after, f.won(), f.survivors(), f.defendersAtStart(), f.defendersLost(), f.inSector(), f.fort());
        String story = f.story("attack", a.target(), attackers);
        if (!f.won())
            return new CommandResult(f.next(), null, 0, story + " — beaten off: all " + q(attackers) + " lost; they lost " + q(f.defendersLost()));
        World[] held = {f.next()};
        String spoiled = capture(cfg, com, held, c.id(), a.target(), f.survivors(), 0);
        return new CommandResult(held[0], null, 0, story + " — taken: " + q(f.survivors()) + " survivors hold it; they lost " + q(f.defendersLost())
                + (spoiled.isEmpty() ? "" : "; lost in the fighting: " + spoiled));
    }

    /**
     * The garrison goes after the guerrillas (issue #72; KNOWN commands/anti.c). Round by round, while both sides stand
     * and the sector has mobility: a soldier falls with chance che × factor / (mil + che) ÷ hap_fact, else a guerrilla.
     * Survive, and the sector pays the rounds in mobility; lose every soldier, and the partisans take the sector back
     * for its old owner, keeping some of their number as che and the rest as its military.
     */
    static CommandResult anti(GameConfig cfg, Commodities com, World w, Country c, Command.Anti a) {
        var unrest = cfg.economy().unrest();
        if (unrest == null) return CommandResult.fail(w, "this world has no guerrillas to hunt");
        if (a.sector() == null || !w.inBounds(a.sector())) return CommandResult.fail(w, "anti where?");
        Sector s = w.sector(a.sector());
        if (s.owner() != c.id()) return CommandResult.fail(w, "you do not own " + a.sector());
        var an = unrest.anti();
        int mil = (int) s.stock().get(com.mil), che = s.che();
        int avail = Math.min(mil, (int) (s.mobility() / an.mobilityPerMil()));
        if (avail <= 0) return CommandResult.fail(w, a.sector() + " has no military or no mobility to send after them");
        if (che <= 0 || s.cheTarget() != c.id()) return CommandResult.fail(w, "no guerrillas are fighting you at " + a.sector());
        var r = new org.hastingtx.empire.engine.update.steps.UnrestStep.R(Rng.stream("anti:" + a.sector() + ":" + w.updateNumber() + ":" + mil + ":" + che, cfg.world() == null ? 0 : cfg.world().seed()));
        double hf = unrest.hapFact(c.levels().happiness(), s.oldOwner() == c.id() ? c.levels().happiness() : w.country(s.oldOwner()).levels().happiness());
        int amil = mil, ache = che, milKilled = 0, cheKilled = 0;
        double mob = s.mobility();
        while (amil != 0 && ache != 0 && mob > 1) {
            double odds = ache * an.cheOddsFactor() / (amil + ache) / hf;
            mob -= an.roundMobility();
            if (r.chance(odds)) { amil--; milKilled++; } else { ache--; cheKilled++; }
        }
        String story = "anti-guerrilla sweep at " + a.sector() + ": " + milKilled + " military and " + cheKilled + " guerrillas killed";
        if (mil - milKilled > 0) {
            Sector n = s.withStock(s.stock().with(com.mil, mil - milKilled)).withMobility(Math.max(0, s.mobility() - cheKilled - milKilled))
                    .withUnrest(s.loyalty(), s.work(), s.occupied() ? s.oldOwner() : Sector.NOBODY, ache, ache > 0 ? c.id() : Sector.NOBODY);
            return new CommandResult(w.withSector(n), null, 0, story + (ache == 0 ? "; the partisans are cleared out for now" : "; " + ache + " still active"));
        }
        // the garrison is gone: the partisans take the sector
        int stay = r.roll0(an.loseRoll());
        int cheLeft = stay > 0 ? ache / (stay + an.loseShareAdd()) : 0;
        int to = s.oldOwner() == c.id() ? Sector.NOBODY : s.oldOwner();
        Sector n = s.withStock(s.stock().with(com.mil, ache - cheLeft)).withMobility(0).withOwner(to)
                .withDistCenter(null).withDeliver(DeliverOrders.none(com.size()))
                .withUnrest((int) (s.loyalty() * an.loyaltyKept()), s.work(), Sector.NOBODY, cheLeft, cheLeft > 0 ? c.id() : Sector.NOBODY);   // the che left behind still fight you (anti.c keeps sct_che_target)
        return new CommandResult(w.withSector(n), null, 0, story + "; the partisans took the sector" + (to < 0 ? "" : " for " + w.country(to).name()) + ". You blew it.");
    }

    private static double fort(GameConfig cfg, CaptureCfg.AssaultCfg ac, Sector s) {
        return cfg.sectorType(s.designation()).hasFlag("defense_bonus") ? ac.fort() : 1;
    }

    private static String q(double v) { return Ledger.q(v); }
}
