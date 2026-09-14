package org.hastingtx.empire.engine.combat;

import org.hastingtx.empire.engine.config.CaptureCfg;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The arithmetic of a salvo (issue #68), shared by the {@code fire} command, which resolves now, and
 * by the update's combat step, which resolves everyone at once. Pure: it takes the numbers and hands
 * back numbers, so the two callers cannot drift apart on what a gun does.
 *
 * <p>A salvo is {@code guns fired × hit per gun × efficiency × tech factor × roll ÷ (1 + armour/100)},
 * taken off the target's efficiency. Guns fired are the fewest of the guns it can bring to bear, the
 * guns it actually has, and what its shells will feed. A hull at or below the sinking line goes down.
 */
public final class Gunnery {
    private Gunnery() {}

    /**
     * Something that can fire: a ship ({@code shipId} ≥ 0) or a coastal sector ({@code shipId} −1).
     * {@code guns} is what it can bring to bear now, {@code shells} what it has to feed them.
     */
    public record Battery(int owner, Coord at, long shipId, double guns, double shells, double efficiency, double tech,
                          int range, double hitPerGun, boolean asw, int sight, String label) {
        public boolean ship() { return shipId >= 0; }
    }

    /**
     * A ship's battery, or null if she cannot fire: unarmed, sunk-in-all-but-name, short-handed, or
     * with no guns or shells aboard.
     */
    public static Battery of(UnitsCfg.ShipsCfg sc, Commodities com, Ship s, String ownerName) {
        UnitsCfg.CombatCfg cc = sc.combat();
        if (cc == null) return null;
        UnitsCfg.ShipClassCfg cls = sc.shipClass(s.cls());
        if (!cls.armed() || s.efficiency() <= cc.sinkAt()) return null;
        if (sc.crews() && s.crew() < cls.crewOr0()) return null;
        double guns = Math.min(cls.gunsOr0(), s.stock().get(com.index("gun")));
        double shells = s.stock().get(com.index("shell"));
        if (guns < 1 || shells < cc.shellsPerGunOr1()) return null;
        return new Battery(s.owner(), s.at(), s.id(), guns, shells, s.efficiency(), s.tech(), cls.rangeOr0(),
                cls.hitPerGun() != null ? cls.hitPerGun() : cc.damagePerGunOr0(), cls.aswOrFalse(), sc.sightOf(cls),
                ownerName + "'s " + cls.name() + " #" + s.id());
    }

    /**
     * A coastal battery: a fort or harbour of its owner with guns, shells and the military to man them,
     * or null. It never finds a submarine; the coast watchers see as far as the guns reach.
     */
    public static Battery of(UnitsCfg.CombatCfg cc, Commodities com, Sector s, Stocks stock, double tech, String ownerName) {
        if (cc == null || !s.owned()) return null;
        UnitsCfg.CombatCfg.Coastal co = cc.coastalOrNone();
        if (!co.designationsOrEmpty().contains(s.designation()) || co.rangeOr0() <= 0) return null;
        double guns = Math.min(co.maxGunsOr0(), Math.min(stock.get(com.index("gun")), Math.floor(stock.get(com.mil) / co.milPerGunOr1())));
        double shells = stock.get(com.index("shell"));
        if (guns < 1 || shells < cc.shellsPerGunOr1() || s.efficiency() <= 0) return null;
        return new Battery(s.owner(), s.at(), -1, guns, shells, s.efficiency(), tech, co.rangeOr0(), cc.damagePerGunOr0(), false, co.rangeOr0(),
                ownerName + "'s guns at " + s.at());
    }

    /** Guns that actually fire: limited by the guns and by the shells to feed them. */
    public static double gunsFired(UnitsCfg.CombatCfg cc, Battery b) {
        return Math.floor(Math.min(b.guns(), b.shells() / cc.shellsPerGunOr1()));
    }

    /** Shells a salvo of {@code guns} uses. Whole shells, like everything else (issue #77). */
    public static double shellsFor(UnitsCfg.CombatCfg cc, double guns) { return Math.floor(guns * cc.shellsPerGunOr1()); }

    /** Efficiency points a salvo takes off a hull with {@code armor}. */
    public static double damage(UnitsCfg.CombatCfg cc, Battery b, double guns, double armor, double roll) {
        return guns * b.hitPerGun() * (b.efficiency() / 100.0) * cc.techFactor(b.tech()) * roll / (1.0 + Math.max(0, armor) / 100.0);
    }

    /** The dice, from a stream the caller keys on who fired at whom and when. */
    public static double roll(UnitsCfg.CombatCfg cc, SplittableRandom r) {
        return cc.rollMin() + (cc.rollMax() - cc.rollMin()) * r.nextDouble();
    }

    /** Whether this battery can hurt that hull at all: a submarine needs anti-submarine weapons. */
    public static boolean canHit(Battery b, UnitsCfg.ShipClassCfg target) { return !target.submarine() || (b.ship() && b.asw()); }

    public static boolean inRange(World w, Battery b, Coord target) { return Hex.distance(w, b.at(), target) <= b.range(); }

    /**
     * Whether {@code b}'s owner may shoot {@code target} without a new order: at war, or she fired on
     * them first and is still marked for it (Richard 2026-09-13). Nothing else — a warship never starts
     * a fight on its own.
     */
    public static boolean hostile(World w, int owner, Ship target, long now) {
        return target.owner() != owner && (w.atWar(owner, target.owner()) || target.firedOnBy(owner, now));
    }

    /** What a class may take aboard: its cargo list, and an armed hull also takes guns and shells. */
    public static boolean takes(UnitsCfg.ShipClassCfg cls, Commodities com, int c) {
        if (cls.armed() && (c == com.index("gun") || c == com.index("shell"))) return true;
        for (String k : cls.carriesOrEmpty()) {
            if (k.equals("all")) return true;
            if (k.equals("goods") && !com.isPerson(c)) return true;
            if (k.equals("people") && com.isPerson(c)) return true;
            if (com.has(k) && com.index(k) == c) return true;
        }
        return false;
    }

    /**
     * A sunk hull's cargo, shared out (Richard 2026-09-13: "salvage by the victor"). {@code capture.cargo_destroyed_fraction}
     * of it is lost in the fighting; the rest goes into the victors' holds in the order given, as far as
     * each has room and may carry it; whatever is left goes down with her. {@code lost} is every unit that
     * leaves the world, by commodity — the caller tallies it.
     */
    public record Salvage(List<Ship> victors, double[] lost, String taken) {}

    public static Salvage salvage(UnitsCfg.ShipsCfg sc, CaptureCfg cap, Commodities com, Ship sunk, List<Ship> victors) {
        double keep = 1.0 - (cap == null ? 0 : cap.cargoDestroyedFraction());
        double[] lost = new double[com.size()];
        List<Ship> out = new ArrayList<>(victors);
        StringBuilder taken = new StringBuilder();
        for (int c = 0; c < com.size(); c++) {
            double aboard = sunk.stock().get(c);
            if (aboard < 1) { lost[c] += aboard; continue; }
            double left = Math.floor(aboard * keep);
            for (int k = 0; k < out.size() && left >= 1; k++) {
                Ship v = out.get(k);
                UnitsCfg.ShipClassCfg vc = sc.shipClass(v.cls());
                if (!takes(vc, com, c)) continue;
                double q = Math.floor(Math.min(left, vc.hold() - v.load()));
                if (q < 1) continue;
                out.set(k, v.withStock(v.stock().plus(c, q)));
                left -= q;
                aboard -= q;
                taken.append(taken.isEmpty() ? "" : ", ").append((long) q).append(' ').append(com.id(c));
            }
            lost[c] += aboard;
        }
        return new Salvage(out, lost, taken.toString());
    }
}
