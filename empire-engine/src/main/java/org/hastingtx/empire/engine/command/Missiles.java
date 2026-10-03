package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.steps.UnrestStep;
import org.hastingtx.empire.engine.view.Visibility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Missiles (issue #71), as Wolfpack Empire launched them: KNOWN commands/laun.c, subs/mslsub.c (msl_launch, msl_hit,
 * msl_abm_intercept), subs/plnsub.c (pln_hitchance, pln_damage) and subs/aircombat.c (sam_intercept). A missile is a
 * plane flown once: no flak or fighter can touch it, but it may blow up on the pad, an ABM may meet it, and it is spent.
 */
final class Missiles {
    private Missiles() {}

    private static String q(double v) { return Ledger.q(v); }

    /**
     * KNOWN plnsub.c pln_hitchance: efficiency × (1 − penalty × tfact) × (1 − acc/100) − how hard the target is; low
     * chances are lifted to a floor curve; at most 100. Returned as a chance, 0..1. The constants are {@code planes.missiles}.
     */
    static double hitChance(UnitsCfg.MissilesCfg mc, UnitsCfg.PlaneClassCfg cls, Plane p, double hard) {
        double t = p.tech() - cls.techRequired();
        double denom = p.tech() - cls.techRequired() / mc.hitTechDivisor();
        double tfact = t <= 0 || denom <= 0 ? 0 : t / denom;
        double hc = p.efficiency() * (1 - mc.hitTechPenalty() * tfact) * (1 - cls.accuracyAt(p.tech()) / 100) - hard;
        if (hc < mc.hitFloor()) hc = mc.hitFloorBase() + mc.hitFloorScale() / (mc.hitFloorOffset() - hc);
        return Math.max(0, Math.min(100, hc)) / 100;
    }

    /** KNOWN msl_launch: it blows up on the pad, less often the fitter and higher-tech it is ({@code planes.missiles.pad_fail_*}). */
    static boolean blowsUp(UnitsCfg.MissilesCfg mc, UnrestStep.R r, Plane p) {
        return r.chance((mc.padFailBase() + (100 - p.efficiency()) / 100) * (1 - mc.techFactor(p.tech())));
    }

    /**
     * KNOWN pln_damage for a missile: roll(load)+1 warheads (at most load), each roll(6) and then 8 on a one-in-ten roll,
     * 5 on a hit by its aim, else 1. Against a sector ({@code ship} false) the aim is 30 + (100 − accuracy), or 30 +
     * accuracy for an anti-ship missile, and doubled unless anti-ship; against a ship the aim is 100 − accuracy, doubled
     * for an anti-ship missile.
     */
    static int damage(GameConfig cfg, UnrestStep.R r, UnitsCfg.PlaneClassCfg cls, Plane p, boolean ship) {
        var bc = cfg.units().planes().bombing();
        int load = (int) cls.loadAt(p.tech());
        if (load < 1) return 0;
        boolean pin = cls.has("marine");
        double acc = cls.accuracyAt(p.tech());
        double aim = ship ? 100 - acc : bc.strategicAimBase() + (pin ? acc : 100 - acc);
        int heads = Math.min(r.roll(load) + 1, load);
        double dam = 0;
        for (int i = 0; i < heads; i++) {
            dam += r.roll(bc.bombRoll());
            int hit = r.roll(100);
            dam += hit >= bc.blamChance() ? bc.blam() : hit < aim ? bc.hit() : bc.miss();
        }
        if (ship == pin) dam *= bc.effectiveMultiple();
        return (int) dam;
    }

    /**
     * KNOWN laun.c launch_missile: a missile of yours at 40%+, on a base it may fly from, launched one way at a sector — or,
     * if it is an anti-ship missile, at a ship — within its range, its warhead the base's shells. SAMs and ABMs are not
     * launched; they rise. At war only, as any attack. It is spent.
     */
    static CommandResult launch(GameConfig cfg, Commodities com, World w, Country c, Command.Launch l) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        if (pc == null || pc.missiles() == null) return CommandResult.fail(w, "these rules have no missiles");
        var mc = pc.missiles();
        Plane p = w.plane(l.missile());
        if (p == null || p.owner() != c.id()) return CommandResult.fail(w, "no missile #" + l.missile() + " of yours");
        UnitsCfg.PlaneClassCfg cls = pc.planeClass(p.cls());
        if (cls != null && cls.has("satellite")) return cls.has("missile") ? Satellites.antiSat(cfg, w, c, p, cls, l) : Satellites.launch(cfg, com, w, c, p, cls, l);
        if (cls == null || !cls.has("missile")) return CommandResult.fail(w, "plane #" + p.id() + " is not a missile; it flies sorties");
        if (cls.has("intercept") || cls.has("sdi")) return CommandResult.fail(w, "a " + cls.name() + " is not launched: it rises against what comes at you");
        if (p.efficiency() < mc.minEfficiency()) return CommandResult.fail(w, "missile #" + p.id() + " is at " + q(p.efficiency()) + "%; it launches at " + q(mc.minEfficiency()) + "% or better");
        String grounded = Air.grounded(cfg, w, p, c.id());
        if (grounded != null) return CommandResult.fail(w, grounded);
        Air.Base base = Air.base(w, p);
        Ship targetShip = null;
        Coord at = l.at();
        if (cls.has("marine")) {
            // only at a ship you can see: one answer for no such ship, a submarine, and one out of sight (KNOWN laun.c:200-212
            // "Bad ship number!"), so a launch is no oracle for where the enemy's fleet is
            Set<Coord> seen = Visibility.of(w, cfg, c.id());
            if (l.ship() != 0) {
                Ship s = w.ship(l.ship());
                if (s != null && s.owner() == c.id()) return CommandResult.fail(w, "ship #" + s.id() + " is yours");
                targetShip = s != null && seen.contains(s.at()) && !cfg.units().ships().shipClass(s.cls()).submarine() ? s : null;
                if (targetShip == null) return CommandResult.fail(w, "no ship #" + l.ship() + " in sight to fire on");
            } else if (at != null && w.inBounds(at)) {
                // NEW: foreign ships reach you as contacts, with no number, so a sector you see names the target too
                Coord there = at;
                if (!seen.contains(there)) return CommandResult.fail(w, there + " is not in sight");
                targetShip = w.ships().stream().filter(s -> s.at().equals(there) && s.owner() != c.id() && w.atWar(c.id(), s.owner())
                        && !cfg.units().ships().shipClass(s.cls()).submarine()).min(Comparator.comparingLong(Ship::id)).orElse(null);
                if (targetShip == null) return CommandResult.fail(w, "no enemy ship in sight at " + there + " to fire on");
            } else return CommandResult.fail(w, "a " + cls.name() + " is fired at a ship: launch " + p.id() + " x,y at one you see, or launch " + p.id() + " ship N");
            if (!w.atWar(c.id(), targetShip.owner())) return CommandResult.fail(w, "ship #" + targetShip.id() + " is " + w.country(targetShip.owner()).name() + "'s; you may fire on her only at war");
            at = targetShip.at();
        } else {
            if (at == null || !w.inBounds(at)) return CommandResult.fail(w, "launch it at where?");
            Sector t = w.sector(at);
            if (!t.owned()) return CommandResult.fail(w, at + " belongs to nobody");
            if (t.owner() == c.id()) return CommandResult.fail(w, at + " is yours");
            String no = Assault.refused(cfg, w, c, t, "strike");
            if (no != null) return CommandResult.fail(w, no);
        }
        int dist = Hex.distance(w, base.at(), at);
        if (dist > cls.rangeAt(p.tech())) return CommandResult.fail(w, at + " is " + dist + " hexes off; a " + cls.name() + " flies " + q(Math.floor(cls.rangeAt(p.tech()))));
        int shell = com.index("shell");
        Nuke nuke = w.nukeOn(p.id());   // issue #71: a nuclear warhead is its load, in place of shells
        double warhead = nuke != null ? 0 : Math.floor(cls.loadAt(p.tech()));
        if (base.stock().get(shell) < warhead) return CommandResult.fail(w, base.name() + " has " + q(base.stock().get(shell)) + " shells; the warhead takes " + q(warhead));

        // spent from here on: the warhead off its base, the missile gone
        World next = base.take(w, shell, warhead).withoutPlane(p.id());
        UnrestStep.R r = new UnrestStep.R(Rng.stream("launch:" + p.id() + ">" + at + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));
        String name = cls.name() + " #" + p.id();
        if (blowsUp(mc, r, p)) return new CommandResult(next, null, 0, name + " blew up on launch");
        if (targetShip == null) {
            // ABMs rise against a missile aimed at a sector (KNOWN msl_abm_intercept)
            Intercepted abm = rise(cfg, r, next, c, "sdi", "ABM", cls.defenseAt(p.tech()), at, w.sector(at).owner());
            next = abm.world();
            if (abm.hit()) return new CommandResult(next, null, 0, name + " launched at " + at + "; " + abm.story());
            if (nuke != null) {   // KNOWN msl_hit: a nuclear missile always hits, and detonates
                Nukes.Blast blast = Nukes.detonate(cfg, com, next, nuke, at);
                return new CommandResult(blast.world(), null, 0, name + " launched at " + at + (abm.story().isEmpty() ? "" : "; " + abm.story()) + ": " + blast.story());
            }
            int dam = damage(cfg, r, cls, p, false);
            next = next.withSector(Spy.damage(cfg, com, r, next.sector(at), dam));
            return new CommandResult(next, null, 0, name + " launched at " + at + (abm.story().isEmpty() ? "" : "; " + abm.story())
                    + ": it hit — " + dam + "% of everything " + w.country(w.sector(at).owner()).name() + " had there");
        }
        // at a ship (KNOWN laun.c, shp_hardtarget): harder the faster she can turn away at sea, easier the more of her to see
        var sc = cfg.units().ships();
        var tcls = sc.shipClass(targetShip.cls());
        // anti-missile ships of hers near her fire first (KNOWN msl_launch, shp_missile_defense)
        Intercepted guns = shipDefense(cfg, com, r, next, c, cls.defenseAt(p.tech()), targetShip.at());
        next = guns.world();
        targetShip = next.ship(targetShip.id());
        if (guns.hit()) return new CommandResult(next, null, 0, name + " launched at ship #" + targetShip.id() + "; " + guns.story());
        String fended = guns.story().isEmpty() ? "" : "; " + guns.story();
        double speed = next.sector(targetShip.at()).terrain() == Terrain.OCEAN ? tcls.speed() / mc.hardTargetSpeedDivisor() : 0;
        double hard = targetShip.efficiency() / 100 * (mc.hardTargetBase() + speed - sc.sightOf(tcls));
        if (!r.chance(hitChance(mc, cls, p, hard))) return new CommandResult(next, null, 0, name + " launched at ship #" + targetShip.id() + fended + ": a splash, and a miss");
        int dam = damage(cfg, r, cls, p, true);
        double hull = dam / (1 + tcls.armorOr0() / 100);
        Ship hit = targetShip.withEfficiency(targetShip.efficiency() - hull);
        if (sc.combat() != null && hit.efficiency() <= sc.combat().sinkAt())
            return new CommandResult(Engagement.sink(cfg, sc, com, next.withShip(hit), hit, c.id()), null, 0, name + " launched at ship #" + targetShip.id() + fended + "; it hit her — and she went down");
        next = next.withShip(hit.withNote("hit by a missile: " + q(hull) + "% of her hull"));
        return new CommandResult(next, null, 0, name + " launched at ship #" + targetShip.id() + fended + "; it hit her: " + q(hull) + "% of her hull");
    }

    /**
     * KNOWN shpsub.c shp_missile_defense: each anti-missile ship of a country at war with you within {@code
     * ship_defense_range} of {@code at}, in order — at {@code ship_defense_min_efficiency} or better, crewed, not for sale,
     * with a gun and {@code ship_defense_shells} in her hold, which she fires — hits with (guns × eff × tech factor ×
     * {@code ship_defense_factor} − the missile's defence)%. The first hit destroys it. You learn whose fire it was, not
     * which ship.
     */
    static Intercepted shipDefense(GameConfig cfg, Commodities com, UnrestStep.R r, World w, Country c, double defence, Coord at) {
        var sc = cfg.units().ships();
        var mc = cfg.units().planes().missiles();
        if (sc == null) return new Intercepted(w, false, "");
        int shell = com.index("shell"), gun = com.index("gun");
        List<String> story = new ArrayList<>();
        World now = w;
        List<Ship> near = w.ships().stream().filter(s -> Hex.distance(now, s.at(), at) <= mc.shipDefenseRange()).sorted(Comparator.comparingLong(Ship::id)).toList();
        for (Ship s : near) {
            UnitsCfg.ShipClassCfg k = sc.shipClass(s.cls());
            if (k == null || k.antiMissileOr0() <= 0 || s.owner() == c.id() || !w.atWar(c.id(), s.owner())) continue;
            if (s.efficiency() < mc.shipDefenseMinEfficiency() || (sc.crews() && s.crew() <= 0)) continue;
            // KNOWN shp_usable_guns: her system brings no more guns than she has aboard
            double guns = Math.min(k.antiMissileOr0(), Math.floor(s.stock().get(gun)));
            if (guns < 1 || s.stock().get(shell) < mc.shipDefenseShells() || w.onTheBlock(TradeLot.SHIP, s.id()) != null) continue;
            w = w.withShip(s.withStock(s.stock().plus(shell, -mc.shipDefenseShells())));
            double teff = s.tech() / (s.tech() + mc.shipDefenseTechScale());
            double hc = Math.max(0, Math.min(100, Math.floor(guns * s.efficiency() / 100 * teff * mc.shipDefenseFactor()) - defence));
            if (r.chance(hc / 100)) { story.add(w.country(s.owner()).name() + "'s anti-missile fire destroyed it"); return new Intercepted(w, true, String.join("; ", story)); }
            story.add(w.country(s.owner()).name() + "'s anti-missile fire missed it");
        }
        return new Intercepted(w, false, String.join("; ", story));
    }

    record Intercepted(World world, boolean hit, String story) {}

    /**
     * KNOWN msl_sel / msl_intercept: at most {@code abms_per_missile} missiles with {@code flag} — "sdi", ABMs against a
     * missile; "satellite", anti-sats against a satellite going up (msl_asat_intercept) — of countries at war with you
     * rise, the target's owner's first, each at 100%, on a base it may fly from, within its range of the target, not for
     * sale. Each may blow up on its own pad; one that flies hits with pln_hitchance against {@code defence}. Every one is
     * spent.
     */
    static Intercepted rise(GameConfig cfg, UnrestStep.R r, World w, Country c, String flag, String label, double defence, Coord at, int targetOwner) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        var mc = pc.missiles();
        List<Plane> abms = new ArrayList<>();
        for (Plane a : w.planes()) {
            UnitsCfg.PlaneClassCfg ac = pc.planeClass(a.cls());
            if (ac == null || !ac.has(flag) || !ac.has("missile") || a.owner() == c.id() || !w.atWar(c.id(), a.owner())) continue;
            if (a.efficiency() < mc.abmEfficiency() || Air.grounded(cfg, w, a, a.owner()) != null) continue;
            if (w.onTheBlock(TradeLot.PLANE, a.id()) != null) continue;
            Air.Base b = Air.base(w, a);
            if (Hex.distance(w, b.at(), at) > ac.rangeAt(a.tech())) continue;
            abms.add(a);
        }
        abms.sort(Comparator.comparing((Plane a) -> a.owner() != targetOwner).thenComparingLong(Plane::id));
        List<String> story = new ArrayList<>();
        for (Plane a : abms.subList(0, Math.min(mc.abmsPerMissile(), abms.size()))) {
            UnitsCfg.PlaneClassCfg ac = pc.planeClass(a.cls());
            w = w.withoutPlane(a.id());
            if (blowsUp(mc, r, a)) { story.add("an " + label + " of " + w.country(a.owner()).name() + " blew up on launch"); continue; }
            if (r.chance(hitChance(mc, ac, a, defence))) {
                story.add("an " + label + " of " + w.country(a.owner()).name() + " shot it down");
                return new Intercepted(w, true, String.join("; ", story));
            }
            story.add("an " + label + " of " + w.country(a.owner()).name() + " missed it");
        }
        return new Intercepted(w, false, String.join("; ", story));
    }

    /**
     * KNOWN aircombat.c sam_intercept: over a sector where it may rise (as a fighter: its own land, or its air-defence area),
     * a SAM of {@code them}'s — within its range of the sector, not twice that — goes at each plane of the raid that cost
     * {@code sam_min_cost} or more, one SAM a plane, the bombers first and then the escorts. It fights as a dogfight
     * would and is always spent.
     */
    static void sams(GameConfig cfg, UnrestStep.R r, World w, int them, Coord at, boolean home, List<Long> mine, List<Long> esc,
                     Map<Long, Plane> now, Set<Long> launched, Set<Long> gone, List<String> story) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        if (pc.missiles() == null || pc.airCombat() == null) return;
        for (List<Long> side : List.of(mine, esc)) {
            for (long id : new ArrayList<>(side)) {
                Plane a = now.get(id);
                UnitsCfg.PlaneClassCfg acls = pc.planeClass(a.cls());
                if (acls.build().getOrDefault("cash", 0.0) < pc.missiles().samMinCost()) continue;
                Plane sam = null;
                List<Plane> theirs = new ArrayList<>(w.planes());
                theirs.sort((x, y) -> Long.compare(y.id(), x.id()));
                for (Plane s : theirs) {
                    UnitsCfg.PlaneClassCfg sc = pc.planeClass(s.cls());
                    if (s.owner() != them || launched.contains(s.id()) || sc == null || !sc.has("missile") || !sc.has("intercept")) continue;
                    if (s.efficiency() < pc.airCombat().minEfficiency() || Air.grounded(cfg, w, s, them) != null || w.onTheBlock(TradeLot.PLANE, s.id()) != null) continue;
                    if (!home && !(s.onAirDefence() && Hex.distance(w, at, s.opPoint()) <= s.radius())) continue;
                    if (Hex.distance(w, Air.base(w, s).at(), at) > sc.rangeAt(s.tech())) continue;
                    sam = s;
                    break;
                }
                if (sam == null) continue;
                UnitsCfg.PlaneClassCfg scls = pc.planeClass(sam.cls());
                launched.add(sam.id());
                int[] dam = Air.dogfight(cfg, r, a, acls, sam, scls);
                Plane hurt = a.withEfficiency(a.efficiency() - dam[0]);
                now.put(id, hurt);
                now.put(sam.id(), sam.withEfficiency(0));   // KNOWN: a SAM takes 100 damage, every time
                gone.add(sam.id());
                story.add("a SAM of " + w.country(them).name() + " rose against #" + id + ": " + dam[0] + "%");
                if (Air.out(pc, r, hurt)) { side.remove(id); gone.add(id); }
            }
        }
    }
}
