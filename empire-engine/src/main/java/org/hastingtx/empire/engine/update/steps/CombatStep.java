package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.combat.Gunnery;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.Step;

import java.util.*;

/**
 * Step 10a (issue #68): every armed ship and coastal battery fires on a hostile ship it can see and
 * reach. Runs after detection, so it aims at where things ended the update.
 *
 * <p><b>Simultaneous.</b> Every battery chooses its target and its damage from the same state, and
 * the damage is applied afterwards, all at once. A ship that is sunk this update still fires this
 * update. Nothing depends on the order anything is scanned in: targets are chosen by distance, then
 * threat, then id, and each roll comes from a stream keyed on who fired at whom and when.
 *
 * <p><b>Only when there is a quarrel.</b> Hostile means at war, or marked for having fired on this
 * country at peace (Richard 2026-09-13). A warship never starts a fight by itself, so a fishing fleet
 * does not blunder into a war.
 *
 * <p><b>Only at what it can see.</b> A fresh contact, or a surface ship within its own sight. A
 * submarine needs a contact and an anti-submarine hull; coastal guns never find one.
 */
public final class CombatStep implements Step {
    public String name() { return "combat"; }

    public void run(Ctx ctx) {
        UnitsCfg.ShipsCfg sc = ctx.cfg.units() == null ? null : ctx.cfg.units().ships();
        UnitsCfg.CombatCfg cc = sc == null ? null : sc.combat();
        if (cc == null || ctx.ships.isEmpty()) return;
        long stamp = ctx.snap.updateNumber() + 1;
        int gun = ctx.com.index("gun"), shell = ctx.com.index("shell");

        // a mark that has run out is forgotten
        for (int i = 0; i < ctx.ships.size(); i++) {
            Ship s = ctx.ships.get(i);
            if (s.firedOn().isEmpty()) continue;
            Map<Integer, Long> live = new TreeMap<>();
            for (var e : s.firedOn().entrySet()) if (e.getValue() >= stamp) live.put(e.getKey(), e.getValue());
            if (live.size() != s.firedOn().size()) ctx.ships.set(i, s.withFiredOn(live));
        }
        if (!anyQuarrel(ctx, stamp)) return;

        List<Gunnery.Battery> batteries = new ArrayList<>();
        Map<Gunnery.Battery, Integer> sectorOf = new HashMap<>();
        for (Ship s : ctx.ships) {
            Gunnery.Battery b = Gunnery.of(sc, ctx.com, s, ctx.country(s.owner()).name());
            if (b != null) batteries.add(b);
        }
        if (!cc.coastalOrNone().designationsOrEmpty().isEmpty()) for (int i : ctx.ownedOrActive()) {
            Sector s = ctx.sector(i);
            if (!s.owned() || !cc.coastalOrNone().designationsOrEmpty().contains(s.designation())) continue;
            Stocks now = s.stock();
            for (int c : new int[] {gun, shell, ctx.com.mil}) now = now.with(c, s.stock().get(c) + ctx.led().st(i, c));
            Gunnery.Battery b = Gunnery.of(cc, ctx.com, s, now, ctx.country(s.owner()).levels().tech(), ctx.country(s.owner()).name());
            if (b != null) { batteries.add(b); sectorOf.put(b, i); }
        }

        // choose and roll, all from the same state
        Map<Long, Double> damage = new TreeMap<>();
        Map<Long, List<String>> hitBy = new TreeMap<>();
        Map<Long, Set<Integer>> victors = new TreeMap<>();
        Map<Long, Double> shellsSpent = new TreeMap<>();
        for (Gunnery.Battery b : batteries) {
            Ship target = choose(ctx, sc, cc, b, stamp);
            if (target == null) {
                if (b.ship() && interdicting(ctx, b.shipId())) {
                    double spent = strikeTrains(ctx, cc, sc.missionsOrDefault(), b, stamp);
                    if (spent > 0) shellsSpent.merge(b.shipId(), spent, Double::sum);
                }
                continue;
            }
            UnitsCfg.ShipClassCfg tc = sc.shipClass(target.cls());
            double guns = Gunnery.gunsFired(cc, b);
            if (guns < 1) continue;
            String key = (b.ship() ? "s" + b.shipId() : "c" + b.at()) + ">" + target.id() + ":" + stamp;
            double dmg = Gunnery.damage(cc, b, guns, tc.armorOr0(), Gunnery.roll(cc, Rng.stream("combat:" + key, ctx.seed)));
            double spent = Gunnery.shellsFor(cc, guns);
            damage.merge(target.id(), dmg, Double::sum);
            hitBy.computeIfAbsent(target.id(), k -> new ArrayList<>()).add(b.label() + " for " + Ledger.q(dmg) + "%");
            victors.computeIfAbsent(target.id(), k -> new TreeSet<>()).add(b.owner());
            String what = "a " + tc.name() + " of " + ctx.country(target.owner()).name() + " at " + target.at();
            if (b.ship()) {
                shellsSpent.merge(b.shipId(), spent, Double::sum);
                note(ctx, b.shipId(), "fired " + (long) guns + (guns == 1 ? " gun" : " guns") + " at " + what + ", hitting for " + Ledger.q(dmg) + "%");
            } else {
                int si = sectorOf.get(b);
                ctx.led().consume(si, shell, spent);
                ctx.led().note(si, "coastal guns fired " + (long) guns + " at " + what + ", hitting for " + Ledger.q(dmg) + "%");
            }
        }
        if (damage.isEmpty() && shellsSpent.isEmpty()) return;

        // apply: shells out of the magazines, damage onto the hulls
        for (int i = 0; i < ctx.ships.size(); i++) {
            Ship s = ctx.ships.get(i);
            Double spent = shellsSpent.get(s.id());
            if (spent != null) { s = s.withStock(s.stock().plus(shell, -spent)); ctx.led().destroyed(shell, spent); }
            Double d = damage.get(s.id());
            if (d != null) {
                s = s.withEfficiency(Math.max(0, s.efficiency() - d));
                ctx.ships.set(i, s);
                note(ctx, s.id(), "hit by " + String.join(" and ", hitBy.get(s.id())) + "; " + Ledger.q(s.efficiency()) + "% left");
            } else ctx.ships.set(i, s);
        }

        // sink what is at or below the line; her cargo is salvaged by the victors in her hex
        List<Ship> sunk = new ArrayList<>();
        for (Ship s : ctx.ships) if (damage.containsKey(s.id()) && s.efficiency() <= cc.sinkAt()) sunk.add(s);
        for (Ship s : sunk) {
            sink(ctx, sc, s, victors.get(s.id()));
            // whatever she carried goes down with her (issue #252)
            for (var u : new java.util.ArrayList<>(ctx.units)) {
                if (u.ship() != s.id()) continue;
                for (int c = 0; c < ctx.com.size(); c++) if (u.stock().get(c) > 0) ctx.led().destroyed[c] += (long) u.stock().get(c);
                ctx.units.remove(u);
                note(ctx, s.id(), "unit #" + u.id() + " went down with her");
            }
        }
    }

    private static boolean interdicting(Ctx ctx, long shipId) {
        for (Ship s : ctx.ships) if (s.id() == shipId) return Ship.INTERDICT.equals(s.mission()) && s.at().equals(s.station());
        return false;
    }

    /**
     * Interdiction (issue #68): a warship on station with nothing afloat to shoot shells the nearest
     * enemy train she can see — rail cargo held on a line within her guns' reach, of a country she is at
     * war with. Each gun destroys {@code missions.train_units_per_gun} units. Only war: a train carries
     * no mark for a peacetime shot, because trains do not fire. Returns the shells she spent.
     */
    private static double strikeTrains(Ctx ctx, UnitsCfg.CombatCfg cc, UnitsCfg.MissionsCfg mc, Gunnery.Battery b, long stamp) {
        if (mc.trainUnitsPerGunOr0() <= 0) return 0;
        int bestIdx = -1, bestD = Integer.MAX_VALUE;
        for (Coord c : org.hastingtx.empire.engine.combat.Blockade.within(ctx.snap, b.at(), Math.min(b.range(), b.sight()))) {
            int i = ctx.idx(c);
            List<HeldParcel> held = ctx.led().heldNext[i] != null ? ctx.led().heldNext[i] : ctx.sector(i).held();
            boolean enemyTrain = false;
            for (HeldParcel p : held) if (p.rail() && p.qty() >= 1 && ctx.snap.atWar(b.owner(), p.owner())) { enemyTrain = true; break; }
            if (!enemyTrain) continue;
            int d = Hex.distance(ctx.snap, b.at(), c);
            if (d < bestD || (d == bestD && c.compareTo(ctx.sector(bestIdx).at()) < 0)) { bestIdx = i; bestD = d; }
        }
        if (bestIdx < 0) return 0;
        double guns = Gunnery.gunsFired(cc, b);
        if (guns < 1) return 0;
        double roll = Gunnery.roll(cc, Rng.stream("interdict:" + b.shipId() + ">" + ctx.sector(bestIdx).at() + ":" + stamp, ctx.seed));
        double budget = Math.floor(guns * mc.trainUnitsPerGunOr0() * (b.efficiency() / 100.0) * cc.techFactor(b.tech()) * roll);
        List<HeldParcel> held = new ArrayList<>(ctx.led().heldNext[bestIdx] != null ? ctx.led().heldNext[bestIdx] : ctx.sector(bestIdx).held());
        StringBuilder hit = new StringBuilder();
        for (int k = 0; k < held.size() && budget >= 1; k++) {
            HeldParcel p = held.get(k);
            if (!p.rail() || !ctx.snap.atWar(b.owner(), p.owner())) continue;
            double q = Math.floor(Math.min(budget, p.qty()));
            if (q < 1) continue;
            budget -= q;
            ctx.led().destroyed(p.commodity(), q);
            held.set(k, p.withQty(p.qty() - q));
            hit.append(hit.isEmpty() ? "" : ", ").append((long) q).append(' ').append(ctx.com.id(p.commodity()));
        }
        int parcels = held.size();
        held.removeIf(p -> p.qty() < 1e-9);
        ctx.led().heldTotal -= parcels - held.size();
        ctx.led().heldNext[bestIdx] = held;
        if (hit.isEmpty()) return 0;
        Coord at = ctx.sector(bestIdx).at();
        note(ctx, b.shipId(), "shelled a train at " + at + ", destroying " + hit);
        ctx.led().note(bestIdx, "train shelled from the sea by " + b.label() + ": " + hit + " destroyed");
        ctx.led().event("train_shelled", ctx.sector(bestIdx).owner(), at, "a train at " + at + " was shelled by " + b.label(), 0);
        return Gunnery.shellsFor(cc, guns);
    }

    /** Whether any ship afloat is hostile to anyone at all. Most updates of most games: no, and the step is free. */
    private static boolean anyQuarrel(Ctx ctx, long stamp) {
        for (Relation r : ctx.snap.relations()) if (r.atWar()) return true;
        for (Ship s : ctx.ships) for (long until : s.firedOn().values()) if (until >= stamp) return true;
        return false;
    }

    /** The target: hostile, in reach, hurtable, visible. Nearest first, then the one with most guns, then the lowest id. */
    private static Ship choose(Ctx ctx, UnitsCfg.ShipsCfg sc, UnitsCfg.CombatCfg cc, Gunnery.Battery b, long stamp) {
        Ship best = null; int bestD = 0; double bestGuns = 0;
        for (Ship t : ctx.ships) {
            if (!Gunnery.hostile(ctx.snap, b.owner(), t, stamp)) continue;
            UnitsCfg.ShipClassCfg tc = sc.shipClass(t.cls());
            if (!Gunnery.canHit(b, tc) || t.efficiency() <= cc.sinkAt()) continue;
            int d = Hex.distance(ctx.snap, b.at(), t.at());
            if (d > b.range() || !visible(ctx, cc, b, t, tc, d, stamp)) continue;
            double g = tc.gunsOr0();
            if (best == null || d < bestD || (d == bestD && (g > bestGuns || (g == bestGuns && t.id() < best.id())))) { best = t; bestD = d; bestGuns = g; }
        }
        return best;
    }

    private static boolean visible(Ctx ctx, UnitsCfg.CombatCfg cc, Gunnery.Battery b, Ship t, UnitsCfg.ShipClassCfg tc, int d, long stamp) {
        for (Contact c : ctx.contacts)
            if (c.owner() == b.owner() && c.shipId() == t.id() && c.age(stamp) <= cc.maxTargetAge()) return true;
        return !tc.submarine() && d <= b.sight();
    }

    private static void note(Ctx ctx, long shipId, String line) {
        List<String> lines = new ArrayList<>(ctx.led().shipNotes.getOrDefault(shipId, List.of()));
        lines.add(line);
        ctx.led().shipNotes.put(shipId, lines);
        for (int i = 0; i < ctx.ships.size(); i++) {
            Ship s = ctx.ships.get(i);
            if (s.id() == shipId) { ctx.ships.set(i, s.withNote(s.note() == null || s.note().isBlank() ? line : s.note() + "; " + line)); return; }
        }
    }

    /**
     * Gone. What was aboard is shared among the victors' ships in her hex; the rest, her fuel and her
     * crew leave the world and are tallied as destroyed, so conservation still balances.
     */
    private static void sink(Ctx ctx, UnitsCfg.ShipsCfg sc, Ship s, Set<Integer> by) {
        UnitsCfg.ShipClassCfg cls = sc.shipClass(s.cls());
        List<Integer> idx = new ArrayList<>();
        List<Ship> near = new ArrayList<>();
        for (int i = 0; i < ctx.ships.size(); i++) {
            Ship v = ctx.ships.get(i);
            if (v.id() != s.id() && by != null && by.contains(v.owner()) && v.at().equals(s.at()) && v.efficiency() > sc.combat().sinkAt()) { idx.add(i); near.add(v); }
        }
        Gunnery.Salvage sal = Gunnery.salvage(sc, ctx.cfg.capture(), ctx.com, s, near);
        for (int k = 0; k < idx.size(); k++) ctx.ships.set(idx.get(k), sal.victors().get(k));
        for (int c = 0; c < ctx.com.size(); c++) if (sal.lost()[c] > 0) ctx.led().destroyed(c, sal.lost()[c]);
        if (sc.fuel() && s.fuel() > 0) ctx.led().destroyed(ctx.com.index(sc.fuelId()), s.fuel());
        if (sc.crews() && s.crew() > 0) ctx.led().destroyed(ShipStep.crewCommodity(ctx, cls), s.crew());
        ctx.ships.removeIf(x -> x.id() == s.id());
        ctx.contacts.removeIf(c -> c.shipId() == s.id());
        String names = by == null ? "" : String.join(" and ", by.stream().map(o -> ctx.country(o).name()).toList());
        note(ctx, s.id(), "sunk at " + s.at() + (names.isEmpty() ? "" : " by " + names));
        ctx.led().event("ship_sunk", s.owner(), s.at(), ctx.country(s.owner()).name() + "'s " + cls.name() + " #" + s.id() + " was sunk" + (names.isEmpty() ? "" : " by " + names), 0);
        if (!sal.taken().isEmpty())
            for (int k = 0; k < near.size(); k++) if (!sal.victors().get(k).stock().equals(near.get(k).stock()))
                note(ctx, near.get(k).id(), "salvaged from the wreck of " + ctx.country(s.owner()).name() + "'s " + cls.name());
    }
}
