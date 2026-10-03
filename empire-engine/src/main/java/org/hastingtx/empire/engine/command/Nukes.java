package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.steps.UnrestStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Nuclear warheads (issue #71; KNOWN buil.c build_nuke, arm.c, subs/detonate.c, subs/damage.c nukedamage). Built whole in
 * a nuclear plant; armed on a plane that can carry one; set off where that plane bombs or that missile comes down.
 */
final class Nukes {
    private Nukes() {}

    private static String q(double v) { return Ledger.q(v); }

    /** KNOWN build_nuke: in a nuclear plant of yours at {@code plant_min_efficiency}, from its materials and your cash, whole. */
    static CommandResult build(GameConfig cfg, Commodities com, World w, Country c, Command.BuildNuke b) {
        UnitsCfg.NukesCfg nc = cfg.units().nukes();
        if (nc == null) return CommandResult.fail(w, "these rules have no nuclear weapons");
        if (c.inSanctuary()) return CommandResult.fail(w, "break sanctuary first");
        if (b.at() == null || !w.inBounds(b.at())) return CommandResult.fail(w, "build where?");
        Sector s = w.sector(b.at());
        if (s.owner() != c.id()) return CommandResult.fail(w, b.at() + " is not yours");
        if (!cfg.sectorType(s.designation()).hasFlag("builds_nukes")) return CommandResult.fail(w, "nuclear weapons are built in a nuclear plant; " + b.at() + " is not one");
        if (s.efficiency() < nc.plantMinEfficiency()) return CommandResult.fail(w, b.at() + " is at " + q(s.efficiency()) + "%; a plant builds at " + q(nc.plantMinEfficiency()) + "% or better");
        UnitsCfg.NukeClassCfg k = nc.nukeClass(b.cls());
        if (k == null) return CommandResult.fail(w, "unknown warhead: " + b.cls());
        if (c.levels().tech() < k.techRequired()) return CommandResult.fail(w, "a " + k.name() + " needs tech " + q(k.techRequired()));
        Stocks st = s.stock();
        double cash = 0;
        StringBuilder used = new StringBuilder();
        for (var e : k.build().entrySet()) {
            double want = e.getValue();
            if (e.getKey().equals("cash")) {
                if (c.cash() < want) return CommandResult.fail(w, "a " + k.name() + " costs $" + q(want) + " and you have $" + q(c.cash()));
                cash = want;
            } else {
                int ci = com.index(e.getKey());
                if (st.get(ci) < want) return CommandResult.fail(w, b.at() + " has " + q(st.get(ci)) + " " + e.getKey() + "; a " + k.name() + " takes " + q(want));
                st = st.plus(ci, -want);
            }
            used.append(used.isEmpty() ? "" : ", ").append(e.getKey().equals("cash") ? "$" + q(want) : q(want) + " " + e.getKey());
        }
        Nuke n = new Nuke(w.nextNukeId(), c.id(), k.id(), b.at(), c.levels().tech(), w.updateNumber(), 0, false);
        World next = w.withSector(s.withStock(st)).withCountry(c.withCash(c.cash() - cash)).withNuke(n);
        return new CommandResult(next, null, 0, "a " + k.name() + " warhead, #" + n.id() + ", is built at " + b.at() + " for " + used);
    }

    /** Whether a plane of this class can carry a warhead at all (KNOWN arm.c: a bomber, tactical or cargo plane, not an anti-ship missile). */
    static boolean carrier(UnitsCfg.PlaneClassCfg cls) {
        return (cls.has("bomber") || cls.has("tactical") || cls.has("cargo")) && !cls.has("marine");
    }

    /**
     * KNOWN arm.c: a warhead of yours in the same sector as a plane of yours that can carry it — its load at least the
     * warhead's weight — not for sale, not in orbit, not already carrying another. It goes off as an airburst or not.
     * Arming clears the plane's mission.
     */
    static CommandResult arm(GameConfig cfg, World w, Country c, Command.Arm a) {
        UnitsCfg.NukesCfg nc = cfg.units().nukes();
        var pc = cfg.units().planes();
        if (nc == null || pc == null) return CommandResult.fail(w, "these rules have no nuclear weapons");
        Plane p = w.plane(a.plane());
        if (p == null || p.owner() != c.id()) return CommandResult.fail(w, "no plane #" + a.plane() + " of yours");
        UnitsCfg.PlaneClassCfg cls = pc.planeClass(p.cls());
        if (cls == null || !carrier(cls)) return CommandResult.fail(w, "a " + (cls == null ? p.cls() : cls.name()) + " cannot carry a nuclear device");
        if (p.orbiting()) return CommandResult.fail(w, "plane #" + p.id() + " is in orbit");
        if (w.onTheBlock(TradeLot.PLANE, p.id()) != null) return CommandResult.fail(w, "plane #" + p.id() + " is for sale");
        Nuke on = w.nukeOn(p.id());
        Nuke n = a.nuke() == 0 ? on : w.nuke(a.nuke());
        if (n == null || w.nukeOwner(n) != c.id()) return CommandResult.fail(w, a.nuke() == 0 ? "arm it with which warhead? arm PLANE NUKE" : "no warhead #" + a.nuke() + " of yours");
        if (on != null && on.id() != n.id()) return CommandResult.fail(w, "plane #" + p.id() + " already carries warhead #" + on.id() + "; disarm it first");
        if (n.armed() && n.plane() != p.id()) return CommandResult.fail(w, "warhead #" + n.id() + " is already armed on plane #" + n.plane());
        UnitsCfg.NukeClassCfg k = nc.nukeClass(n.cls());
        if (cls.loadAt(p.tech()) < k.weight()) return CommandResult.fail(w, "a " + cls.name() + " cannot carry a " + k.name() + " (it weighs " + q(k.weight()) + ")");
        if (!w.nukeAt(n).equals(Air.base(w, p).at())) return CommandResult.fail(w, "warhead #" + n.id() + " is at " + w.nukeAt(n) + ", not with plane #" + p.id());
        World next = w.withNuke(n.armedOn(p, a.airburst())).withPlane(p.withMission(null, null, 0));
        return new CommandResult(next, null, 0, "plane #" + p.id() + " is armed with warhead #" + n.id() + ", a " + k.name() + ", set to " + (a.airburst() ? "airburst" : "groundburst"));
    }

    /** KNOWN arm.c c_disarm: off the plane, into the sector it is in — yours. */
    static CommandResult disarm(GameConfig cfg, World w, Country c, Command.Disarm d) {
        Plane p = w.plane(d.plane());
        if (p == null || p.owner() != c.id()) return CommandResult.fail(w, "no plane #" + d.plane() + " of yours");
        Nuke n = w.nukeOn(p.id());
        if (n == null) return CommandResult.fail(w, "plane #" + p.id() + " carries no warhead");
        if (w.onTheBlock(TradeLot.PLANE, p.id()) != null) return CommandResult.fail(w, "plane #" + p.id() + " is for sale");
        Coord at = Air.base(w, p).at();
        if (w.sector(at).owner() != c.id()) return CommandResult.fail(w, at + " is not yours; disarm it on your own ground");
        return new CommandResult(w.withNuke(n.disarmed(c.id(), at)), null, 0, "warhead #" + n.id() + " is off plane #" + p.id() + " and stored at " + at);
    }

    /**
     * KNOWN nukedamage: how much a warhead does {@code range} sectors from ground zero. A groundburst does
     * damage/(range + 1); an airburst reaches further but does less at the centre, damage × centre − falloff × range.
     * Under {@code min_damage}, none.
     */
    static int damage(UnitsCfg.NukesCfg nc, UnitsCfg.NukeClassCfg k, int range, boolean airburst) {
        int reach = airburst ? (int) (k.blast() * nc.airburstReach()) : k.blast();
        if (reach < range) return 0;
        int dam = airburst ? (int) (k.damage() * nc.airburstCentre() - range * nc.airburstFalloff()) : (int) (k.damage() / (range + 1.0));
        return dam < nc.minDamage() ? 0 : dam;
    }

    record Blast(World world, String story) {}

    /**
     * KNOWN detonate.c: a warhead goes off over {@code at}. Within its radius — the blast for an airburst, two-thirds of it
     * for a groundburst, and only ground zero over the sea — every sector takes {@link #damage} as sect_damage (a sanctuary
     * shrugs it off), and over {@code wasteland_above} land becomes a radioactive wasteland nobody owns. Planes on the ground
     * or on a deck, land units, ships (a submarine at sea only at ground zero) and other warheads there are hit too —
     * none of a country in sanctuary, nor in a sanctuary. {@code by} is told what it did to the land, and what of its
     * own was caught; what it caught of anyone else's is theirs to learn (KNOWN: the original tells each owner).
     */
    static Blast detonate(GameConfig cfg, Commodities com, World w, Nuke n, Coord at, int by) {
        UnitsCfg.NukesCfg nc = cfg.units().nukes();
        UnitsCfg.NukeClassCfg k = nc.nukeClass(n.cls());
        boolean air = n.airburst();
        UnrestStep.R r = new UnrestStep.R(Rng.stream("nuke:" + n.id() + ">" + at + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));
        int rad = air ? k.blast() : (int) Math.floor(k.blast() * nc.groundburstRadius());
        if (!w.sector(at).isLand()) rad = 0;   // KNOWN: one falling on water affects only its own sector
        w = w.withoutNuke(n.id());
        Set<Coord> zone = Hex.within(w, at, rad);
        List<String> story = new ArrayList<>();
        int waste = 0, hurt = 0;
        for (Coord c : zone) {
            int dam = damage(nc, k, Hex.distance(w, at, c), air);
            if (dam <= 0) continue;
            Sector s = w.sector(c);
            if (s.sanctuary()) { story.add("it bounced off the sanctuary at " + c); continue; }
            Sector hit = Spy.damage(cfg, com, r, s, dam);
            if (dam > nc.wastelandAbove() && s.isLand()) {
                hit = hit.withOwner(Sector.NOBODY).withDesignation("wasteland", 0).withUnrest(0, 100, Sector.NOBODY, 0, 0);
                waste++;
            } else hurt++;
            w = w.withSector(hit);
        }
        if (waste > 0) story.add(waste + (waste == 1 ? " sector" : " sectors") + " left a radioactive wasteland");
        if (hurt > 0) story.add(hurt + (hurt == 1 ? " sector" : " sectors") + " damaged");

        // planes on the ground or on a deck (not satellites up there; not aboard a submarine at sea, unless at ground zero)
        var pc = cfg.units().planes();
        var sc = cfg.units().ships();
        int planes = 0, units = 0, ships = 0, warheads = 0;
        if (pc != null) for (Plane p : List.copyOf(w.planes())) {
            if (p.orbiting()) continue;
            Ship deck = p.aboard() ? w.ship(p.ship()) : null;
            Coord where = deck != null ? deck.at() : p.at();
            if (!zone.contains(where) || (deck != null && submergedAway(w, sc, deck, at)) || sheltered(w, p.owner(), where)) continue;
            int dam = damage(nc, k, Hex.distance(w, at, where), air);
            if (dam <= 0) continue;
            double eff = p.efficiency() - dam;
            w = eff < pc.minEfficiency() ? w.withoutPlane(p.id()) : w.withPlane(p.withEfficiency(eff));
            if (p.owner() == by) planes++;
        }
        var lc = cfg.units().land();
        if (lc != null) for (LandUnit u : List.copyOf(w.units())) {
            Ship deck = u.ship() != 0 ? w.ship(u.ship()) : null;
            Coord where = deck != null ? deck.at() : u.at();
            if (!zone.contains(where) || (deck != null && submergedAway(w, sc, deck, at)) || sheltered(w, u.owner(), where)) continue;
            int dam = damage(nc, k, Hex.distance(w, at, where), air);
            if (dam <= 0) continue;
            double eff = u.efficiency() - dam;
            w = eff < lc.startEfficiency() ? w.withoutUnit(u.id()) : w.withUnit(u.withEfficiency(eff));
            if (u.owner() == by) units++;
        }
        if (sc != null) for (Ship s : List.copyOf(w.ships())) {
            if (w.ship(s.id()) == null || !zone.contains(s.at()) || submergedAway(w, sc, s, at) || sheltered(w, s.owner(), s.at())) continue;
            int dam = damage(nc, k, Hex.distance(w, at, s.at()), air);
            if (dam <= 0) continue;
            Ship hit = s.withEfficiency(s.efficiency() - dam);
            w = sc.combat() != null && hit.efficiency() <= sc.combat().sinkAt() ? Engagement.sink(cfg, sc, com, w.withShip(hit), hit, Sector.NOBODY) : w.withShip(hit);
            if (s.owner() == by) ships++;
        }
        // other warheads there go with chance damage% (KNOWN)
        for (Nuke o : List.copyOf(w.nukes())) {
            if (w.nuke(o.id()) == null || !zone.contains(w.nukeAt(o)) || sheltered(w, w.nukeOwner(o), w.nukeAt(o))) continue;
            int dam = damage(nc, k, Hex.distance(w, at, w.nukeAt(o)), air);
            if (dam > 0 && r.chance(dam / 100.0)) { if (w.nukeOwner(o) == by) warheads++; w = w.withoutNuke(o.id()); }
        }
        if (planes > 0) story.add(planes + " of your planes caught");
        if (units > 0) story.add(units + " of your units caught");
        if (ships > 0) story.add(ships + " of your ships caught");
        if (warheads > 0) story.add(warheads + " of your warheads destroyed");
        String how = (air ? "an airburst" : "a groundburst") + " of a " + k.name() + " over " + at;
        return new Blast(w, how + (story.isEmpty() ? "" : ": " + String.join("; ", story)));
    }

    /** A sanctuary cannot be touched (issue #54): neither a country in it, nor anything in a sanctuary sector. */
    private static boolean sheltered(World w, int owner, Coord where) {
        return (owner >= 0 && owner < w.countries().size() && w.country(owner).inSanctuary()) || w.sector(where).sanctuary();
    }

    /** KNOWN detonate.c: a submarine out at sea is untouched unless the warhead came down right on her. */
    private static boolean submergedAway(World w, UnitsCfg.ShipsCfg sc, Ship s, Coord at) {
        return sc != null && sc.shipClass(s.cls()).submarine() && !w.sector(s.at()).isLand() && !s.at().equals(at);
    }
}
