package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.steps.UnrestStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Planes (issue #262, #71 slice 3a), as the original flew them: built on an airfield (KNOWN sect.config {@code *},
 * commands/buil.c), flown as a sortie that takes its petrol and its bombs off that field (KNOWN subs/plnsub.c
 * {@code pln_equip}), shot at by the flak of what it flies against (KNOWN subs/aircombat.c), and bombing by
 * {@code pln_damage}.
 *
 * <p>A plane's range is the round trip, so it strikes at half of it. It comes home to its own field the same turn.
 * On the way, the fighters of any country at war with you whose land it crosses rise against it, and the escorts it
 * took fight them first (issue #71, slice 3b; KNOWN aircombat.c {@code ac_encounter}, {@code ac_dog}).
 */
final class Air {
    private Air() {}

    private static String q(double v) { return Ledger.q(v); }

    /** KNOWN commands/buil.c build_plane: laid down on an airfield at a tenth of its materials and cash. */
    static CommandResult build(GameConfig cfg, Commodities com, World w, Country c, Command.BuildPlane b) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        if (pc == null) return CommandResult.fail(w, "this world has no planes");
        if (c.inSanctuary()) return CommandResult.fail(w, "break sanctuary first");
        if (b.at() == null || !w.inBounds(b.at())) return CommandResult.fail(w, "build where?");
        Sector s = w.sector(b.at());
        if (s.owner() != c.id()) return CommandResult.fail(w, b.at() + " is not yours");
        if (!cfg.sectorType(s.designation()).hasFlag("builds_planes")) return CommandResult.fail(w, b.at() + " is not an airfield; designate one first");
        UnitsCfg.PlaneClassCfg cls = pc.planeClass(b.cls());
        if (cls == null) return CommandResult.fail(w, "unknown plane class: " + b.cls());
        if (c.levels().tech() < cls.techRequired()) return CommandResult.fail(w, article(cls.name()) + " needs tech " + q(cls.techRequired()));

        double share = pc.startEfficiency() / 100.0;
        Stocks st = s.stock();
        double cash = 0;
        StringBuilder used = new StringBuilder();
        for (var e : cls.build().entrySet()) {
            double want = Math.ceil(e.getValue() * share);
            if (e.getKey().equals("cash")) {
                if (c.cash() < want) return CommandResult.fail(w, "it costs $" + q(want) + " to lay down and you have $" + q(c.cash()));
                cash = want;
            } else {
                int ci = com.index(e.getKey());
                if (st.get(ci) < want) return CommandResult.fail(w, b.at() + " has " + q(st.get(ci)) + " " + e.getKey() + "; it takes " + q(want));
                st = st.plus(ci, -want);
            }
            used.append(used.isEmpty() ? "" : ", ").append(e.getKey().equals("cash") ? "$" + q(want) : q(want) + " " + e.getKey());
        }
        Plane p = new Plane(w.nextPlaneId(), c.id(), cls.id(), b.at(), pc.startEfficiency(), c.levels().tech(), w.updateNumber(), "");
        World next = w.withSector(s.withStock(st)).withCountry(c.withCash(c.cash() - cash)).withPlane(p);
        return new CommandResult(next, null, 0, article(cls.name()) + " #" + p.id() + " is on the field at " + b.at()
                + " at " + q(pc.startEfficiency()) + "% for " + used + "; it fits out there");
    }

    /**
     * Air defence (KNOWN miss.c): a fighter only; its op point within its operating range — half its range, as far as it
     * strikes — of its field; the radius at most that range too (miss.c:264-273). It rises over any sector in its area.
     */
    static CommandResult mission(GameConfig cfg, World w, Country c, Command.AirMission m) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        if (pc == null || pc.airCombat() == null) return CommandResult.fail(w, "these rules have no air combat");
        Plane p = w.plane(m.plane());
        if (p == null || p.owner() != c.id()) return CommandResult.fail(w, "no plane #" + m.plane() + " of yours");
        UnitsCfg.PlaneClassCfg cls = pc.planeClass(p.cls());
        if (m.off()) {
            if (!p.onAirDefence()) return CommandResult.fail(w, "plane #" + p.id() + " is on no mission");
            return new CommandResult(w.withPlane(p.withMission(null, null, 0)), null, 0, "plane #" + p.id() + " comes off air defence; it still rises over your own land");
        }
        if (cls == null || !cls.has("intercept")) return CommandResult.fail(w, "only fighters fly air defence; " + article(cls == null ? p.cls() : cls.name()) + " does not");
        if (m.op() == null || !w.inBounds(m.op())) return CommandResult.fail(w, "air defence around where?");
        int oprange = (int) Math.floor(cls.reachAt(p.tech()));
        Base b = base(w, p);
        if (b == null) return CommandResult.fail(w, "plane #" + p.id() + " has no ship to fly from");
        int d = Hex.distance(w, b.at(), m.op());
        if (d > oprange) return CommandResult.fail(w, m.op() + " is " + d + " hexes from " + b.name() + "; it guards within " + oprange);
        if (m.radius() < 0 || m.radius() != Math.rint(m.radius())) return CommandResult.fail(w, "the radius is a whole number of hexes");
        int radius = m.radius() == 0 ? oprange : (int) Math.min(oprange, m.radius());
        return new CommandResult(w.withPlane(p.withMission(Plane.AIR_DEFENCE, m.op(), radius)), null, 0,
                "plane #" + p.id() + " flies air defence within " + radius + " of " + m.op() + ": at war, it rises against raids over any sector there"
                        + (m.radius() > oprange ? " (" + oprange + " is as far as it reaches)" : ""));
    }

    // ------------------------------------------------------------------------------- bases: fields and carriers

    /**
     * Where a plane flies from and what it draws on (issue #71; KNOWN plnsub.c pln_airbase_ok, pln_equip): an airfield of
     * its owner's, or the carrier it is aboard — her hold is its petrol, bombs and cargo, and she is where it takes off.
     */
    record Base(Sector field, Ship ship) {
        Coord at() { return ship != null ? ship.at() : field.at(); }
        Stocks stock() { return ship != null ? ship.stock() : field.stock(); }
        String name() { return ship != null ? "ship #" + ship.id() : field.at().toString(); }
        boolean same(Base o) { return ship != null ? o.ship != null && o.ship.id() == ship.id() : o.ship == null && o.field.at().equals(field.at()); }
        /** The world with {@code q} of {@code ci} taken off this base, as it stands in {@code w}. */
        World take(World w, int ci, double q) {
            if (ship != null) { Ship sh = w.ship(ship.id()); return w.withShip(sh.withStock(sh.stock().plus(ci, -q))); }
            Sector sec = w.sector(field.at());
            return w.withSector(sec.withStock(sec.stock().plus(ci, -q)));
        }
        /** The same base as it stands in {@code w}. */
        Base in(World w) { return ship != null ? new Base(null, w.ship(ship.id())) : new Base(w.sector(field.at()), null); }
    }

    static Base base(World w, Plane p) {
        if (p.aboard()) { Ship sh = w.ship(p.ship()); return sh == null ? null : new Base(null, sh); }
        return new Base(w.sector(p.at()), null);
    }

    /** Where a plane may sit on a carrier (KNOWN carrier_planes, inc_shp_nplane): a helicopter, an extra-light or a light plane. */
    private static boolean carrierPlane(UnitsCfg.PlaneClassCfg cls) { return cls.has("light") || cls.has("helo") || cls.has("xlight"); }

    /** "missile" or "satellite" for a class that is launched rather than flown (issue #71), else null. */
    static String rocket(UnitsCfg.PlaneClassCfg cls) { return cls.has("missile") ? "missile" : cls.has("satellite") ? "satellite" : null; }

    /**
     * Null when a plane may take off from its base (KNOWN pln_airbase_ok); otherwise why not. An airfield of its owner's at
     * {@code field_min_efficiency}; or its owner's carrier at {@code carrier_min_efficiency}, of a class that works that
     * kind of plane. Never a satellite in orbit.
     */
    static String grounded(GameConfig cfg, World w, Plane p, int owner) {
        if (p.orbiting()) return "plane #" + p.id() + " is in orbit";   // issue #71: a satellite up there flies from nowhere
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        double fieldMin = pc.airCombat() == null ? 0 : pc.airCombat().fieldMinEfficiency();
        Base b = base(w, p);
        if (b == null) return "plane #" + p.id() + " has no ship to fly from";
        if (b.ship() != null) {
            Ship sh = b.ship();
            var sc = cfg.units().ships();
            UnitsCfg.PlaneClassCfg cls = pc.planeClass(p.cls());
            if (sh.owner() != owner) return "ship #" + sh.id() + " is not yours";
            // a ship for sale does nothing (issue #141): no aircraft fly from her, nor draw on her hold
            if (w.onTheBlock(TradeLot.SHIP, sh.id()) != null) return "ship #" + sh.id() + " is for sale; no aircraft fly from her";
            if (sc == null || !sc.shipClass(sh.cls()).carriesPlanes() || cls == null || !carrierPlane(cls)) return "plane #" + p.id() + " cannot fly from ship #" + sh.id();
            double min = pc.carrierMinEfficiency() == null ? 0 : pc.carrierMinEfficiency();
            if (sh.efficiency() < min) return "ship #" + sh.id() + " is at " + q(sh.efficiency()) + "%; a carrier works aircraft at " + q(min) + "% or better";
            return null;
        }
        Sector f = b.field();
        if (f.owner() != owner) return "plane #" + p.id() + " is on a field that is no longer yours";
        UnitsCfg.PlaneClassCfg vc = pc.planeClass(p.cls());
        if (vc != null && vc.has("vtol") && f.isLand()) return null;   // KNOWN pln_airbase_ok: a VTOL type needs no airfield
        if (!cfg.sectorType(f.designation()).hasFlag("builds_planes")) return "plane #" + p.id() + " is at " + f.at() + ", which is not an airfield";
        if (f.efficiency() < fieldMin) return f.at() + " is at " + q(f.efficiency()) + "%; planes take off from an airfield at " + q(fieldMin) + "% or better";
        return null;
    }

    /**
     * Room aboard a carrier for {@code adding} more planes (KNOWN ship_can_carry): helicopters in their own slots and then the
     * fixed-wing ones, extra-light in theirs, light planes in the fixed-wing slots.
     */
    static boolean roomAboard(GameConfig cfg, World w, Ship sh, List<Plane> adding) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        var sclass = cfg.units().ships().shipClass(sh.cls());
        int helo = 0, xl = 0, light = 0;
        List<Plane> all = new ArrayList<>(adding);
        for (Plane p : w.planes()) if (p.ship() == sh.id() && adding.stream().noneMatch(a -> a.id() == p.id())) all.add(p);
        for (Plane p : all) {
            UnitsCfg.PlaneClassCfg cls = pc.planeClass(p.cls());
            if (cls == null || !carrierPlane(cls)) return false;
            if (cls.has("helo")) helo++; else if (cls.has("xlight")) xl++; else light++;
        }
        if (xl > sclass.xlightOr0()) return false;
        return light + Math.max(0, helo - sclass.choppersOr0()) <= sclass.planesOr0();
    }

    // ---------------------------------------------------------------------------------- air transport (#71)

    /** The planes of a transport sortie, ready: all from one field, fit, able to fly the leg, the petrol taken. */
    private record Lift(String fail, World world, List<Plane> planes, Base base) {}

    /**
     * KNOWN plnsub.c pln_sel and pln_equip: every plane yours, at {@code min_efficiency} or better, on the same field of
     * yours, with {@code flags}, able to fly {@code mult} times the leg (one way to land, there and back otherwise), and
     * each sortie's petrol off the field.
     */
    private static Lift lift(GameConfig cfg, Commodities com, World w, Country c, List<Long> ids, Coord to, int mult, List<String> flags, String verb) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        if (pc == null || pc.transport() == null) return new Lift("these rules have no air transport", w, List.of(), null);
        if (ids.isEmpty()) return new Lift(verb + " which planes?", w, List.of(), null);
        if (to == null || !w.inBounds(to)) return new Lift(verb + " where?", w, List.of(), null);
        double minEff = pc.airCombat() == null ? pc.minEfficiency() : pc.airCombat().minEfficiency();
        int pet = com.index("pet");
        List<Plane> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Base base = null;
        double fuel = 0;
        for (long id : ids) {
            Plane p = w.plane(id);
            if (p == null || p.owner() != c.id()) return new Lift("no plane #" + id + " of yours", w, List.of(), null);
            if (!seen.add(id)) return new Lift("plane #" + id + " is named twice", w, List.of(), null);
            UnitsCfg.PlaneClassCfg cls = pc.planeClass(p.cls());
            if (cls == null) return new Lift("plane #" + id + " has no class in these rules", w, List.of(), null);
            if (rocket(cls) != null) return new Lift("plane #" + id + " is a " + rocket(cls) + ": it is launched, not flown (launch " + id + " x,y)", w, List.of(), null);   // KNOWN pln_sel nowant P_M|P_O
            for (String f : flags) if (!cls.has(f)) return new Lift(article(cls.name()) + " cannot " + verb + "; " + (f.equals("para") ? "it takes a transport that drops paratroops" : "it takes a cargo plane"), w, List.of(), null);
            if (p.efficiency() < minEff) return new Lift("plane #" + id + " is at " + q(p.efficiency()) + "%; it flies at " + q(minEff) + "% or better", w, List.of(), null);
            String grounded = grounded(cfg, w, p, c.id());
            if (grounded != null) return new Lift(grounded, w, List.of(), null);
            Base b = base(w, p);
            if (base == null) base = b;
            else if (!base.same(b)) return new Lift("plane #" + id + " is at " + b.name() + ", not " + base.name() + "; a sortie flies from one field or one carrier", w, List.of(), null);
            int leg = Hex.distance(w, b.at(), to);
            if (leg * mult > cls.rangeAt(p.tech())) return new Lift(to + " is " + leg + " hexes off; plane #" + id + " flies " + q(Math.floor(cls.rangeAt(p.tech())))
                    + (mult == 1 ? " one way" : " there and back"), w, List.of(), null);
            fuel += cls.fuel();
            out.add(p);
        }
        if (base.stock().get(pet) < fuel) return new Lift(base.name() + " has " + q(base.stock().get(pet)) + " petrol; the sortie takes " + q(fuel), w, List.of(), null);
        w = base.take(w, pet, fuel);
        return new Lift(null, w, out, base.in(w));
    }

    /** What each plane carries (KNOWN pln_equip: load × the mission's multiple ÷ the commodity's weight), loaded in turn from {@code have}. */
    private static Map<Long, Double> loads(GameConfig cfg, Commodities com, List<Plane> planes, int ci, double multiple, double have) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        double weight = cfg.commodities().get(ci).weight();
        Map<Long, Double> out = new java.util.LinkedHashMap<>();
        for (Plane p : planes) {
            UnitsCfg.PlaneClassCfg cls = pc.planeClass(p.cls());
            double cap = cls.has("cargo") ? Math.floor(cls.loadAt(p.tech()) * multiple / weight) : 0;
            double take = Math.min(cap, have);
            have -= take;
            out.put(p.id(), take);
        }
        return out;
    }

    /** KNOWN fly.c, pln_equip: civilians fly only from land whose own people they are, and only into land of yours that is. */
    private static String civilianRule(Commodities com, int ci, Base from, Sector to, int me) {
        if (ci != com.civ) return null;
        if (from.field() != null && from.field().occupied()) return "the civilians at " + from.name() + " are a conquered people and will not board";
        if (to.owner() != me || to.occupied()) return "civilians fly only into land of yours whose people they are";
        return null;
    }

    /**
     * KNOWN fly.c: planes fly one way to an airfield of yours at {@code landing_min_efficiency} or better and stay there;
     * transports carry their load twice over ({@code fly_load_multiple}); escorts fly along and land with them. The
     * fighters of anyone at war rise on the way, and what a plane shot down or turned back carried is lost.
     */
    static CommandResult fly(GameConfig cfg, Commodities com, World w, Country c, Command.Fly f, Market.Room room) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        Lift l = lift(cfg, com, w, c, f.planes(), f.to(), 1, List.of(), "fly");
        if (l.fail() != null) return CommandResult.fail(w, l.fail());
        var tc = pc.transport();
        Sector to = w.sector(f.to());
        // a carrier of yours there with the room takes them aboard (KNOWN pln_where_to_land offers carriers first); else an airfield
        Ship carrier = null;
        List<Plane> landing = new ArrayList<>(l.planes());
        for (long e : f.escorts()) { Plane ep = w.plane(e); if (ep != null) landing.add(ep); }
        for (Ship sh : w.shipsAt(f.to())) {
            if (sh.owner() != c.id() || cfg.units().ships() == null || !cfg.units().ships().shipClass(sh.cls()).carriesPlanes()) continue;
            if (sh.efficiency() < (pc.carrierMinEfficiency() == null ? 0 : pc.carrierMinEfficiency())) continue;
            if (l.base().ship() != null && l.base().ship().id() == sh.id()) continue;
            if (w.onTheBlock(TradeLot.SHIP, sh.id()) != null) continue;   // for sale: nothing lands on her (issue #141)
            if (roomAboard(cfg, w, sh, landing)) { carrier = sh; break; }
        }
        if (carrier == null) {
            if (to.owner() != c.id() || !cfg.sectorType(to.designation()).hasFlag("builds_planes")) return CommandResult.fail(w, f.to() + " is not an airfield of yours to land on, nor is there a carrier of yours there with the room");
            if (to.efficiency() < tc.landingMinEfficiency()) return CommandResult.fail(w, f.to() + " is at " + q(to.efficiency()) + "%; planes land at " + q(tc.landingMinEfficiency()) + "% or better");
            if (l.base().ship() == null && f.to().equals(l.base().at())) return CommandResult.fail(w, "they are already at " + f.to());
        } else if (f.commodity() != null) return CommandResult.fail(w, "a carrier takes planes, not cargo: fly the " + f.commodity() + " to an airfield");
        World next = l.world();
        Map<Long, Double> load = Map.of();
        int ci = -1;
        if (f.commodity() != null) {
            if (!com.has(f.commodity())) return CommandResult.fail(w, "unknown commodity: " + f.commodity());
            ci = com.index(f.commodity());
            String no = civilianRule(com, ci, l.base(), to, c.id());
            if (no != null) return CommandResult.fail(w, no);
            // no more than the field can take: what the update would cut away is lost (issue #103)
            load = loads(cfg, com, l.planes(), ci, tc.flyLoadMultiple(), Math.floor(Math.min(l.base().stock().get(ci), room.roomFor(to, ci))));
            double total = load.values().stream().mapToDouble(Double::doubleValue).sum();
            if (total < 1) return CommandResult.fail(w, "nothing to carry: " + (l.base().stock().get(ci) < 1 ? l.base().name() + " has no " + f.commodity()
                    : room.roomFor(to, ci) < 1 ? f.to() + " has no room for more " + f.commodity() : "none of them is a cargo plane"));
            next = l.base().take(next, ci, total);
        }
        Escorts es = escorts(cfg, com, next, c, f.escorts(), l.planes(), f.to(), 1);
        if (es.fail() != null) return CommandResult.fail(w, es.fail());
        UnrestStep.R r = new UnrestStep.R(Rng.stream("fly:" + f.planes() + ">" + f.to() + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));
        Raid raid = encounter(cfg, com, r, es.world(), c, l.planes(), es.planes(), f.to());
        next = raid.world();
        double delivered = 0;
        for (long id : raid.through()) {
            Plane p = next.plane(id);
            Plane landed = p.withShip(carrier != null ? carrier.id() : 0, f.to());
            next = next.withPlane(landed.withMission(null, null, 0).withNote(carrier != null ? "landed on ship #" + carrier.id() : "flew to " + f.to()));   // a mission was set from the old base
            delivered += load.getOrDefault(id, 0.0);
        }
        if (!raid.lost()) for (long id : raid.escortsThrough()) { Plane p = next.plane(id); if (p != null) next = next.withPlane(p.withShip(carrier != null ? carrier.id() : 0, f.to()).withMission(null, null, 0)); }
        if (delivered > 0) { Sector d = next.sector(f.to()); next = next.withSector(d.withStock(d.stock().plus(ci, delivered))); }
        double carried = load.values().stream().mapToDouble(Double::doubleValue).sum();
        String what = carried > 0 ? "; " + q(delivered) + " of " + q(carried) + " " + f.commodity() + " arrived" : "";
        return new CommandResult(next, null, 0, join(raid.story(), raid.through().size() + " of " + l.planes().size() + " landed " + (carrier != null ? "on ship #" + carrier.id() + " at " : "at ") + f.to() + what));
    }

    /** KNOWN drop.c: transports drop their load (once over) on a sector of yours and fly home; nothing lands. */
    static CommandResult drop(GameConfig cfg, Commodities com, World w, Country c, Command.Drop d, Market.Room room) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        // shells on the sea are mines (KNOWN drop.c, pln_mine)
        if ("shell".equals(d.commodity()) && d.at() != null && w.inBounds(d.at()) && !w.sector(d.at()).isLand()) return mineDrop(cfg, com, w, c, d);
        Lift l = lift(cfg, com, w, c, d.planes(), d.at(), 2, List.of("cargo"), "drop supplies");
        if (l.fail() != null) return CommandResult.fail(w, l.fail());
        Sector to = w.sector(d.at());
        if (to.owner() != c.id() || !to.terrain().isLand()) return CommandResult.fail(w, "drop supplies on land of yours; " + d.at() + " is not");
        if (d.commodity() == null || !com.has(d.commodity())) return CommandResult.fail(w, "drop what?");
        int ci = com.index(d.commodity());
        String no = civilianRule(com, ci, l.base(), to, c.id());
        if (no != null) return CommandResult.fail(w, no);
        Map<Long, Double> load = loads(cfg, com, l.planes(), ci, pc.transport().dropLoadMultiple(), Math.floor(Math.min(l.base().stock().get(ci), room.roomFor(to, ci))));
        double total = load.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total < 1) return CommandResult.fail(w, l.base().stock().get(ci) < 1 ? l.base().name() + " has no " + d.commodity() + " to drop" : d.at() + " has no room for more " + d.commodity());
        World next = l.base().take(l.world(), ci, total);
        Escorts es = escorts(cfg, com, next, c, d.escorts(), l.planes(), d.at(), 2);
        if (es.fail() != null) return CommandResult.fail(w, es.fail());
        UnrestStep.R r = new UnrestStep.R(Rng.stream("drop:" + d.planes() + ">" + d.at() + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));
        Raid raid = encounter(cfg, com, r, es.world(), c, l.planes(), es.planes(), d.at());
        next = raid.world();
        double delivered = 0;
        for (long id : raid.through()) { delivered += load.getOrDefault(id, 0.0); next = next.withPlane(next.plane(id).withNote("dropped " + d.commodity() + " on " + d.at())); }
        if (delivered > 0) { Sector s = next.sector(d.at()); next = next.withSector(s.withStock(s.stock().plus(ci, delivered))); }
        return new CommandResult(next, null, 0, join(raid.story(), q(delivered) + " of " + q(total) + " " + d.commodity() + " fell on " + d.at() + "; the planes flew home"));
    }

    /**
     * KNOWN drop.c and pln_mine: mine-laying planes carry twice their load in shells ({@code plane_drop_multiple}) and drop
     * them on the sea as mines, a shell a mine, and fly home. What a plane shot down or turned back carried is lost.
     */
    private static CommandResult mineDrop(GameConfig cfg, Commodities com, World w, Country c, Command.Drop d) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        UnitsCfg.MinesCfg mc = cfg.units().mines();
        if (mc == null) return CommandResult.fail(w, "these rules have no mines");
        Lift l = lift(cfg, com, w, c, d.planes(), d.at(), 2, List.of("mine"), "drop mines");
        if (l.fail() != null) return CommandResult.fail(w, l.fail());
        int shell = com.index("shell");
        double have = Math.floor(l.base().stock().get(shell)), total = 0;
        Map<Long, Double> load = new java.util.LinkedHashMap<>();
        for (Plane p : l.planes()) {
            double take = Math.min(Math.floor(pc.planeClass(p.cls()).loadAt(p.tech()) * mc.planeDropMultiple() / cfg.commodities().get(shell).weight()), have - total);
            load.put(p.id(), take);
            total += take;
        }
        if (total < 1) return CommandResult.fail(w, l.base().name() + " has no shells to make mines of");
        World next = l.base().take(l.world(), shell, total);
        Escorts es = escorts(cfg, com, next, c, d.escorts(), l.planes(), d.at(), 2);
        if (es.fail() != null) return CommandResult.fail(w, es.fail());
        UnrestStep.R r = new UnrestStep.R(Rng.stream("minedrop:" + d.planes() + ">" + d.at() + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));
        Raid raid = encounter(cfg, com, r, es.world(), c, l.planes(), es.planes(), d.at());
        next = raid.world();
        double laid = 0;
        for (long id : raid.through()) { laid += load.getOrDefault(id, 0.0); next = next.withPlane(next.plane(id).withNote("dropped mines at " + d.at())); }
        Sector sea = next.sector(d.at());
        if (laid > 0) next = next.withSector(sea.withMines(sea.mines() + (int) laid));
        return new CommandResult(next, null, 0, join(raid.story(), q(laid) + " of " + q(total) + " mines went into the sea at " + d.at() + "; the planes flew home"));
    }

    /**
     * KNOWN reco.c sweep and pln_sweep: mine-sweeping planes fly to {@code at} over the sea and home; in each sea hex on the
     * way each plane clears at most one mine, with chance (100 − accuracy)/100. No shells come back.
     */
    static CommandResult sweep(GameConfig cfg, Commodities com, World w, Country c, Command.SweepAir s) {
        if (cfg.units().mines() == null) return CommandResult.fail(w, "these rules have no mines");
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        Lift l = lift(cfg, com, w, c, s.planes(), s.at(), 2, List.of("sweep"), "sweep");
        if (l.fail() != null) return CommandResult.fail(w, l.fail());
        Escorts es = escorts(cfg, com, l.world(), c, s.escorts(), l.planes(), s.at(), 2);
        if (es.fail() != null) return CommandResult.fail(w, es.fail());
        UnrestStep.R r = new UnrestStep.R(Rng.stream("sweep:" + s.planes() + ">" + s.at() + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));
        Raid raid = encounter(cfg, com, r, es.world(), c, l.planes(), es.planes(), s.at());
        World next = raid.world();
        int cleared = 0;
        for (Coord at : flightPath(next, l.base().at(), s.at())) {
            Sector sea = next.sector(at);
            if (sea.isLand() || sea.mines() <= 0) continue;
            int gone = 0;
            for (long id : raid.through()) {
                Plane p = next.plane(id);
                if (sea.mines() - gone > 0 && r.chance((100 - pc.planeClass(p.cls()).accuracyAt(p.tech())) / 100.0)) gone++;
            }
            if (gone > 0) { next = next.withSector(sea.withMines(sea.mines() - gone)); cleared += gone; }
        }
        for (long id : raid.through()) next = next.withPlane(next.plane(id).withNote("swept for mines to " + s.at()));
        return new CommandResult(next, null, 0, join(raid.story(), cleared == 0 ? "they found no mines" : "they swept " + cleared + (cleared == 1 ? " mine" : " mines")));
    }

    /**
     * KNOWN para.c: transports that drop paratroops carry their load of soldiers from their field; past the fighters and
     * the flak over the target, the survivors land and fight. Not on your own land, nor on mountains, the sea, a capital,
     * a fortress or a wasteland. The planes fly home.
     */
    static CommandResult paradrop(GameConfig cfg, Commodities com, World w, Country c, Command.Paradrop pd) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        Lift l = lift(cfg, com, w, c, pd.planes(), pd.at(), 2, List.of("cargo", "para"), "paradrop");
        if (l.fail() != null) return CommandResult.fail(w, l.fail());
        var tc = pc.transport();
        Sector target = w.sector(pd.at());
        if (target.owner() == c.id()) return CommandResult.fail(w, pd.at() + " is yours; paratroops go where you are not");
        if (tc.noParadropTerrain().contains(target.terrain().name().toLowerCase(java.util.Locale.ROOT))) return CommandResult.fail(w, "paratroops cannot land on " + target.terrain().name().toLowerCase(java.util.Locale.ROOT));
        if (tc.noParadropDesignations().contains(target.designation())) return CommandResult.fail(w, "paratroops cannot take a " + target.designation());
        if (target.owned()) { String no = Assault.refused(cfg, w, c, target, "paradrop on"); if (no != null) return CommandResult.fail(w, no); }
        Map<Long, Double> load = loads(cfg, com, l.planes(), com.mil, tc.dropLoadMultiple(), Math.floor(l.base().stock().get(com.mil)));
        double total = load.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total < 1) return CommandResult.fail(w, l.base().name() + " has no soldiers to drop");
        World next = l.base().take(l.world(), com.mil, total);
        Escorts es = escorts(cfg, com, next, c, pd.escorts(), l.planes(), pd.at(), 2);
        if (es.fail() != null) return CommandResult.fail(w, es.fail());
        UnrestStep.R r = new UnrestStep.R(Rng.stream("para:" + pd.planes() + ">" + pd.at() + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));
        Raid raid = encounter(cfg, com, r, es.world(), c, l.planes(), es.planes(), pd.at());
        next = raid.world();
        // the flak over the drop zone, plane by plane; whoever is shot down or turns back takes their stick with them
        double troops = 0;
        List<String> story = new ArrayList<>();
        if (!raid.story().isEmpty()) story.add(raid.story());
        for (long id : raid.through()) {
            Plane p = next.plane(id);
            Flak fk = raid.unseenAtTarget() ? new Flak(next, p, false, false, "") : flak(cfg, com, r, next, p, pc.planeClass(p.cls()), next.sector(pd.at()));
            next = fk.world();
            if (!fk.story().isEmpty()) story.add(fk.story() + (fk.shotDown() ? ", plane #" + id + " shot down" : fk.aborted() ? ", plane #" + id + " turned back" : ""));
            if (fk.shotDown() || fk.aborted()) continue;
            troops += load.getOrDefault(id, 0.0);
            next = next.withPlane(next.plane(id).withNote("dropped paratroops on " + pd.at()));
        }
        if (troops < 1) return new CommandResult(next, null, 0, join(String.join("; ", story), "no paratroops reached " + pd.at() + "; " + q(total) + " lost"));
        return Assault.paradrop(cfg, com, next, c, pd.at(), troops, tc.paradropStrength(), String.join("; ", story));
    }

    /** What a sortie needs off the field, and what it costs when it flies. */
    private record Sortie(CommandResult fail, Plane plane, UnitsCfg.PlaneClassCfg cls, Base base, Sector target, double bombs) {}

    private static Sortie ready(GameConfig cfg, Commodities com, World w, Country c, long id, Coord at, boolean bombing) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        if (pc == null) return new Sortie(CommandResult.fail(w, "this world has no planes"), null, null, null, null, 0);
        Plane p = w.plane(id);
        if (p == null || p.owner() != c.id()) return new Sortie(CommandResult.fail(w, "no plane #" + id + " of yours"), null, null, null, null, 0);
        UnitsCfg.PlaneClassCfg cls = pc.planeClass(p.cls());
        if (cls == null) return new Sortie(CommandResult.fail(w, "plane #" + id + " has no class in these rules"), null, null, null, null, 0);
        if (rocket(cls) != null) return new Sortie(CommandResult.fail(w, "plane #" + id + " is a " + rocket(cls) + ": it is launched, not flown (launch " + id + " x,y)"), null, null, null, null, 0);
        if (p.efficiency() < pc.minEfficiency()) return new Sortie(CommandResult.fail(w, "plane #" + id + " is wreckage at " + q(p.efficiency()) + "%"), null, null, null, null, 0);
        if (at == null || !w.inBounds(at)) return new Sortie(CommandResult.fail(w, "fly where?"), null, null, null, null, 0);
        String grounded = grounded(cfg, w, p, c.id());
        if (grounded != null) return new Sortie(CommandResult.fail(w, grounded), null, null, null, null, 0);
        Base field = base(w, p);
        double reach = cls.reachAt(p.tech());
        int dist = Hex.distance(w, field.at(), at);
        if (dist > reach) return new Sortie(CommandResult.fail(w, at + " is " + dist + " hexes off; " + article(cls.name())
                + " flies " + q(Math.floor(cls.rangeAt(p.tech()))) + " there and back, so it strikes at " + q(Math.floor(reach))), null, null, null, null, 0);
        int pet = com.index("pet");
        if (field.stock().get(pet) < cls.fuel()) return new Sortie(CommandResult.fail(w, field.name() + " has "
                + q(field.stock().get(pet)) + " petrol; the sortie takes " + q(cls.fuel())), null, null, null, null, 0);
        double bombs = 0;
        if (bombing && w.nukeOn(p.id()) == null) {   // a warhead aboard is its load: no bombs (issue #71)
            if (cls.loadAt(p.tech()) < 1) return new Sortie(CommandResult.fail(w, article(cls.name()) + " carries no bombs"), null, null, null, null, 0);
            bombs = Math.min(cls.loadAt(p.tech()), Math.floor(field.stock().get(com.index("shell"))));
            if (bombs < 1) return new Sortie(CommandResult.fail(w, field.name() + " has no shells to bomb with"), null, null, null, null, 0);
        }
        return new Sortie(null, p, cls, field, w.sector(at), bombs);
    }

    /**
     * KNOWN plnsub.c pln_damage and commands/bomb.c: {@code roll(load)+1} bombs, each {@code roll(6)} and then 8 on a
     * one-in-ten roll, 5 on a hit by the plane's accuracy, else 1; doubled when the plane is the right kind for the
     * job — a tactical bomber on a pinpoint raid, a bomber on a strategic one. The sector takes it as sect_damage.
     */
    static CommandResult bomb(GameConfig cfg, Commodities com, World w, Country c, Command.Bomb b) {
        Sortie so = ready(cfg, com, w, c, b.plane(), b.at(), true);
        if (so.fail() != null) return so.fail();
        Sector target = so.target();
        if (target.owner() == c.id()) return CommandResult.fail(w, b.at() + " is yours");
        if (!target.owned()) return CommandResult.fail(w, b.at() + " belongs to nobody");
        String no = Assault.refused(cfg, w, c, target, "bomb");
        if (no != null) return CommandResult.fail(w, no);

        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        var bc = pc.bombing();
        Plane p = so.plane();
        UnitsCfg.PlaneClassCfg cls = so.cls();
        UnrestStep.R r = new UnrestStep.R(Rng.stream("bomb:" + p.id() + ">" + b.at() + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));

        // off the field: petrol for the flight, shells for the bombs
        World next = so.base().take(so.base().take(w, com.index("pet"), cls.fuel()), com.index("shell"), so.bombs());

        // the escorts take their petrol, and whoever is at war with you and under the flight path rises against it
        Escorts es = escorts(cfg, com, next, c, b.escorts(), p, b.at());
        if (es.fail() != null) return CommandResult.fail(w, es.fail());
        Raid raid = encounter(cfg, com, r, es.world(), c, p, es.planes(), b.at());
        next = raid.world();
        String air = raid.story();
        if (raid.lost()) return new CommandResult(next, null, 0, air);
        p = next.plane(p.id());

        // the flak over the target, before it can drop anything — unless it came in unseen (KNOWN do_evade)
        Flak flak = raid.unseenAtTarget() ? new Flak(next, p, false, false, "") : flak(cfg, com, r, next, p, cls, target);
        next = flak.world();
        if (flak.shotDown()) return new CommandResult(next, null, 0, join(air, flak.story()) + " — plane #" + p.id() + " was shot down over " + b.at());
        p = flak.plane();
        if (flak.aborted()) return new CommandResult(next, null, 0, join(air, flak.story()) + " — plane #" + p.id() + " turned back at " + q(p.efficiency()) + "%");

        // a warhead aboard goes off over the target instead of bombs (issue #71; KNOWN strat_bomb, detonate)
        Nuke warhead = next.nukeOn(p.id());
        if (warhead != null) {
            Nukes.Blast blast = Nukes.detonate(cfg, com, next, warhead, target.at());
            Plane home = blast.world().plane(p.id());
            next = home == null ? blast.world() : blast.world().withPlane(home.withNote("dropped a warhead on " + target.at()));
            String before = join(air, flak.story());
            return new CommandResult(next, null, 0, (before.isEmpty() ? "" : before + " — ") + "plane #" + p.id() + " dropped warhead #" + warhead.id() + ": " + blast.story());
        }

        boolean pinpoint = b.pinpoint();
        boolean tactical = cls.has("tactical");
        double aim = pinpoint ? 100 - cls.accuracyAt(p.tech())
                : bc.strategicAimBase() + (tactical ? cls.accuracyAt(p.tech()) : 100 - cls.accuracyAt(p.tech()));
        boolean effective = pinpoint == tactical;
        int bombs = (int) Math.min(so.bombs(), r.roll((int) so.bombs()));
        double dam = 0;
        for (int i = 0; i < bombs; i++) {
            dam += r.roll(bc.bombRoll());
            int hit = r.roll(100);
            dam += hit >= bc.blamChance() ? bc.blam() : hit < aim ? bc.hit() : bc.miss();
        }
        if (effective) dam *= bc.effectiveMultiple();
        int damage = (int) dam;
        next = next.withSector(Spy.damage(cfg, com, r, next.sector(target.at()), damage))
                   .withPlane(p.withNote("bombed " + target.at()));
        String before = join(air, flak.story());
        return new CommandResult(next, null, 0, (before.isEmpty() ? "" : before + " — ")
                + "plane #" + p.id() + " dropped " + bombs + (bombs == 1 ? " bomb" : " bombs") + " on " + b.at() + ": "
                + damage + "% of everything " + w.country(target.owner()).name() + " had there"
                + (p.efficiency() < 100 ? ", and came home at " + q(p.efficiency()) + "%" : ""));
    }

    /** KNOWN commands/reco.c: it flies over and you see the sector as it is now, flak and all. */
    static CommandResult recon(GameConfig cfg, Commodities com, World w, Country c, Command.Recon rc) {
        Sortie so = ready(cfg, com, w, c, rc.plane(), rc.at(), false);
        if (so.fail() != null) return so.fail();
        Plane p = so.plane();
        UnitsCfg.PlaneClassCfg cls = so.cls();
        UnrestStep.R r = new UnrestStep.R(Rng.stream("recon:" + p.id() + ">" + rc.at() + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));
        World next = so.base().take(w, com.index("pet"), cls.fuel());
        Escorts es = escorts(cfg, com, next, c, rc.escorts(), p, rc.at());
        if (es.fail() != null) return CommandResult.fail(w, es.fail());
        Raid raid = encounter(cfg, com, r, es.world(), c, p, es.planes(), rc.at());
        next = raid.world();
        String air = raid.story();
        if (raid.lost()) return new CommandResult(next, null, 0, air + " — it told you nothing");
        p = next.plane(p.id());

        Sector target = next.sector(rc.at());
        Flak flak = raid.unseenAtTarget() ? new Flak(next, p, false, false, "") : flak(cfg, com, r, next, p, cls, target);
        next = flak.world();
        String before = join(air, flak.story());
        if (flak.shotDown()) return new CommandResult(next, null, 0, before + " — plane #" + p.id() + " was shot down over " + rc.at() + " and told you nothing");
        p = flak.plane();
        next = next.withPlane(p.withNote("flew over " + rc.at()));
        // what it saw goes onto your chart, as sight does (issue #64)
        next = remember(next, c.id(), target);
        String who = target.owned() ? next.country(target.owner()).name() : "nobody";
        String what = target.owned() && target.owner() != c.id()
                ? who + "'s " + target.designation().replace('_', ' ') + " at " + q(target.efficiency()) + "%, "
                  + q(Math.floor(target.stock().get(com.civ))) + " civilians and " + q(Math.floor(target.stock().get(com.mil))) + " soldiers"
                : target.designation().replace('_', ' ') + " at " + rc.at() + ", " + who;
        return new CommandResult(next, null, 0, (before.isEmpty() ? "" : before + " — ")
                + "plane #" + p.id() + " flew over " + rc.at() + ": " + what
                + (flak.aborted() ? "; it was hit and turned for home at " + q(p.efficiency()) + "%" : ""));
    }

    /** What the flight saw goes on this country's chart, the way sight does (issue #64). */
    private static World remember(World w, int owner, Sector s) {
        java.util.List<SeenSector> seen = new java.util.ArrayList<>(w.seen());
        seen.removeIf(x -> x.owner() == owner && x.at().equals(s.at()));
        seen.add(new SeenSector(owner, s.at(), s.terrain(), s.owner(), s.designation(), w.updateNumber()));
        return w.withSeen(seen);
    }

    private record Flak(World world, Plane plane, boolean shotDown, boolean aborted, String story) {}

    /**
     * KNOWN aircombat.c ac_doflak / ac_flak_dam: the sector's guns, at most {@code gun_max}, doubled for tech, fire
     * once; the damage is {@code (roll(8) + 2) ×} the table's multiplier for {@code guns − the plane's defence}. Below
     * the class minimum it is shot down; under 80% it may turn back.
     */
    private static Flak flak(GameConfig cfg, Commodities com, UnrestStep.R r, World w, Plane p, UnitsCfg.PlaneClassCfg cls, Sector target) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        var fc = pc.flak();
        if (target.owner() == p.owner() || !target.owned()) return new Flak(w, p, false, false, "");
        double guns = Math.min(fc.gunMax(), Math.floor(target.stock().get(com.index("gun"))));
        if (guns < 1) return new Flak(w, p, false, false, "");
        guns = r.roundavg(guns * fc.gunMultiple());
        double dam = (r.roll(fc.roll()) + fc.rollOffset()) * fc.multiplier(guns, cls.defenseAt(p.tech()), cls.has("tactical"));
        double eff = Math.max(0, p.efficiency() - dam);
        String story = "flak over " + target.at() + " took " + (int) dam + "%";
        if (eff < pc.minEfficiency()) return new Flak(w.withoutPlane(p.id()), p, true, false, story);
        Plane hit = p.withEfficiency(eff);
        boolean abort = eff < pc.abortBelow() && r.chance((pc.abortBelow() - eff) / 100.0);
        return new Flak(w.withPlane(hit), hit, false, abort, story);
    }

    private static String join(String a, String b) { return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; }

    // ------------------------------------------------------------------------------------- air to air (#71)

    private record Escorts(String fail, World world, List<Plane> planes) {}

    /**
     * The escorts a sortie takes (KNOWN plnsub.c pln_sel): fighters or escort planes of yours, fit to fly, on a field of
     * yours within {@code escort_reach} of the one the raid flies from, with the range to get there, on to the target and
     * back, and each taking its own petrol off its own field. A bad escort refuses the whole sortie, before anything flies.
     */
    private static Escorts escorts(GameConfig cfg, Commodities com, World w, Country c, List<Long> ids, Plane lead, Coord target) {
        return escorts(cfg, com, w, c, ids, List.of(lead), target, 2);
    }

    /** {@code mult}: 2 for a sortie that comes home, 1 for one that lands where it goes (KNOWN rangemult). */
    private static Escorts escorts(GameConfig cfg, Commodities com, World w, Country c, List<Long> ids, List<Plane> leads, Coord target, int mult) {
        Plane lead = leads.get(0);
        Set<Long> flying = new HashSet<>(leads.stream().map(Plane::id).toList());
        if (ids.isEmpty()) return new Escorts(null, w, List.of());
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        UnitsCfg.AirCombatCfg ac = pc.airCombat();
        if (ac == null) return new Escorts("these rules have no air combat, so no escorts", w, List.of());
        int pet = com.index("pet");
        List<Plane> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int leg = Hex.distance(w, base(w, lead).at(), target);
        for (long id : ids) {
            Plane e = w.plane(id);
            if (e == null || e.owner() != c.id()) return new Escorts("no plane #" + id + " of yours to fly escort", w, List.of());
            if (flying.contains(id) || !seen.add(id)) return new Escorts("plane #" + id + " is named twice", w, List.of());
            UnitsCfg.PlaneClassCfg cls = pc.planeClass(e.cls());
            if (cls == null || !(cls.has("intercept") || cls.has("escort")) || cls.has("missile")) return new Escorts("plane #" + id + " cannot fly escort; fighters and escort planes can", w, List.of());
            if (e.efficiency() < ac.minEfficiency()) return new Escorts("plane #" + id + " is at " + q(e.efficiency()) + "%; an escort needs " + q(ac.minEfficiency()) + "%", w, List.of());
            String grounded = grounded(cfg, w, e, c.id());
            if (grounded != null) return new Escorts(grounded, w, List.of());
            Base field = base(w, e), leadBase = base(w, lead);
            int toLead = Hex.distance(w, field.at(), leadBase.at());
            if (toLead > ac.escortReach()) return new Escorts("plane #" + id + " is " + toLead + " hexes from " + leadBase.name() + "; escorts fly from within " + ac.escortReach(), w, List.of());
            if ((toLead + leg) * mult > cls.rangeAt(e.tech())) return new Escorts("plane #" + id + " cannot fly to " + target + (mult == 2 ? " and back" : "") + ": it flies " + q(Math.floor(cls.rangeAt(e.tech()))) + (mult == 2 ? " there and back" : ""), w, List.of());
            if (field.stock().get(pet) < cls.fuel()) return new Escorts(field.name() + " has " + q(field.stock().get(pet)) + " petrol; plane #" + id + "'s sortie takes " + q(cls.fuel()), w, List.of());
            w = field.take(w, pet, cls.fuel());
            out.add(e);
        }
        return new Escorts(null, w, out);
    }

    /**
     * What the flight out did to the raid: {@code through} the lead planes that got through, in order — the rest were shot
     * down or turned back. {@code lost} when none did.
     */
    private record Raid(World world, List<Long> through, List<Long> escortsThrough, String story, boolean unseenAtTarget) {
        boolean lost() { return through.isEmpty(); }
    }

    /** A raid of one lead plane. */
    private static Raid encounter(GameConfig cfg, Commodities com, UnrestStep.R r, World w, Country c, Plane lead, List<Plane> escorts, Coord target) {
        return encounter(cfg, com, r, w, c, List.of(lead), escorts, target);
    }

    /**
     * The flight out (KNOWN aircombat.c ac_encounter): hex by hex from the field to the target. Over each sector, every
     * country at war with you gets its chance: over its own land any fighter of its may rise, elsewhere only those flying
     * air defence over that sector — fit to fly ({@code min_efficiency}), on a working airfield of theirs, with the range
     * to reach the sector and come back and their petrol on the field; newest first, as many as the raid has planes and
     * one more, and each only once a raid. Your escorts fight them first, then the lead planes fight whoever is left.
     * Flak is not here: it fires over the target, after.
     */
    private static Raid encounter(GameConfig cfg, Commodities com, UnrestStep.R r, World w, Country c, List<Plane> leads, List<Plane> escorts, Coord target) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        UnitsCfg.AirCombatCfg ac = pc.airCombat();
        List<Long> mine = new ArrayList<>(leads.stream().map(Plane::id).toList());
        if (ac == null) return new Raid(w, mine, escorts.stream().map(Plane::id).toList(), "", false);
        int pet = com.index("pet");
        Map<Long, Plane> now = new HashMap<>();
        for (Plane l : leads) now.put(l.id(), l);
        for (Plane e : escorts) now.put(e.id(), e);
        List<Long> esc = new ArrayList<>(escorts.stream().map(Plane::id).toList());
        Set<Long> launched = new HashSet<>(), gone = new HashSet<>();
        List<String> story = new ArrayList<>();
        boolean unseenAtTarget = false;
        for (Coord at : flightPath(w, base(w, leads.get(0)).at(), target)) {
            Sector s = w.sector(at);
            // KNOWN aircombat.c do_evade: the raid slips past unseen with the chance of its least stealthy plane
            double evade = 1.0;
            for (long id : mine) evade = Math.min(evade, pc.planeClass(now.get(id).cls()).stealthOr0() / 100.0);
            for (long id : esc) evade = Math.min(evade, pc.planeClass(now.get(id).cls()).stealthOr0() / 100.0);
            if (evade > 0 && r.chance(evade)) {
                if (at.equals(target)) unseenAtTarget = true;
                continue;
            }
            // KNOWN aircombat.c:195-201: every country at war with the raider gets its chance over every sector — over its own
            // land any fighter of its may rise, elsewhere only those flying air defence over that sector (only_mission)
            for (int them = 0; them < w.countries().size() && !mine.isEmpty(); them++) {
                if (them == c.id() || !w.atWar(c.id(), them)) continue;
                boolean home = s.owned() && s.owner() == them;
                // their SAMs first (KNOWN sam_intercept): one at each plane of the raid that costs enough, bombers then escorts
                Missiles.sams(cfg, r, w, them, at, home, mine, esc, now, launched, gone, story);
                if (mine.isEmpty()) break;
                // who rises: a snapshot of their fighters, newest first
                List<Plane> up = new ArrayList<>();
                int room = mine.size() + esc.size() + ac.extraInterceptors();
                List<Plane> theirs = new ArrayList<>(w.planes());
                theirs.sort((a, b) -> Long.compare(b.id(), a.id()));
                for (Plane f : theirs) {
                    if (up.size() >= room) break;
                    if (f.owner() != them || launched.contains(f.id())) continue;
                    if (w.onTheBlock(TradeLot.PLANE, f.id()) != null) continue;   // KNOWN aircombat.c:773: not one on the trading block
                    UnitsCfg.PlaneClassCfg fc = pc.planeClass(f.cls());
                    if (fc == null || !fc.has("intercept") || fc.has("missile") || f.efficiency() < ac.minEfficiency()) continue;
                    if (!home && !(f.onAirDefence() && Hex.distance(w, at, f.opPoint()) <= f.radius())) continue;
                    if (grounded(cfg, w, f, them) != null) continue;   // KNOWN pln_airbase_ok: a field of theirs, or a carrier
                    Base field = base(w, f);
                    if (fc.rangeAt(f.tech()) < 2 * Hex.distance(w, field.at(), at)) continue;
                    if (field.stock().get(pet) < fc.fuel()) continue;
                    w = field.take(w, pet, fc.fuel());
                    launched.add(f.id());
                    now.put(f.id(), f);
                    up.add(f);
                }
                if (up.isEmpty()) continue;
                story.add(up.size() + (up.size() == 1 ? " fighter" : " fighters") + " of " + w.country(them).name() + " rose over " + at);
                List<Long> ups = new ArrayList<>(up.stream().map(Plane::id).toList());
                // the escorts first, then the lead planes against whoever is left (KNOWN ac_intercept)
                airToAir(cfg, r, now, esc, ups, gone, story);
                if (!ups.isEmpty()) airToAir(cfg, r, now, mine, ups, gone, story);
            }
            if (mine.isEmpty()) break;
        }
        // what came of it, on the planes and in the world
        Set<Long> leadIds = new HashSet<>(leads.stream().map(Plane::id).toList());
        for (Plane p : now.values()) {
            if (gone.contains(p.id()) && p.efficiency() < pc.minEfficiency()) { w = w.withoutPlane(p.id()); continue; }
            Plane was = w.plane(p.id());
            if (was == null) continue;
            String note = p.owner() == c.id() ? (leadIds.contains(p.id()) ? was.note() : "flew escort to " + target) : "rose against a raid by " + c.name();
            w = w.withPlane(p.withNote(note));
        }
        for (Plane l : leads) {
            if (mine.contains(l.id())) continue;
            Plane after = now.get(l.id());
            story.add(after.efficiency() < pc.minEfficiency() ? "plane #" + l.id() + " was shot down on the way" : "plane #" + l.id() + " turned back at " + q(after.efficiency()) + "%");
        }
        if (unseenAtTarget) story.add("unseen over " + target);
        return new Raid(w, mine, esc, String.join("; ", story), unseenAtTarget);
    }

    /**
     * KNOWN aircombat.c ac_airtoair: the two lists are paired round and round, each advancing, until both have been
     * gone through once; a plane shot down or turned back leaves its list. Each pairing is a dogfight.
     */
    private static void airToAir(GameConfig cfg, UnrestStep.R r, Map<Long, Plane> now, List<Long> raid, List<Long> ints, Set<Long> gone, List<String> story) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        int n = Math.max(raid.size(), ints.size()), ai = 0, di = 0;
        for (int i = 0; i < n && !raid.isEmpty() && !ints.isEmpty(); i++) {
            ai %= raid.size();
            di %= ints.size();
            long a = raid.get(ai), d = ints.get(di);
            Plane pa = now.get(a), pd = now.get(d);
            int[] dam = dogfight(cfg, r, pa, pc.planeClass(pa.cls()), pd, pc.planeClass(pd.cls()));
            Plane na = pa.withEfficiency(pa.efficiency() - dam[0]), nd = pd.withEfficiency(pd.efficiency() - dam[1]);
            now.put(a, na);
            now.put(d, nd);
            story.add("#" + a + " against their #" + d + ": yours lost " + dam[0] + "%, theirs " + dam[1] + "%");
            if (out(pc, r, na)) { raid.remove(ai); gone.add(a); } else ai++;
            if (out(pc, r, nd)) { ints.remove(di); gone.add(d); } else di++;
        }
    }

    /** Shot down below the minimum, or turned back under {@code abort_below} with chance (80 − efficiency)/100 (KNOWN ac_damage_plane). */
    static boolean out(UnitsCfg.PlanesCfg pc, UnrestStep.R r, Plane p) {
        return p.efficiency() < pc.minEfficiency() || (p.efficiency() < pc.abortBelow() && r.chance((pc.abortBelow() - p.efficiency()) / 100.0));
    }

    /**
     * KNOWN aircombat.c ac_dog: the raid plane's attack (its defence if it has none) and the interceptor's defence, each
     * by efficiency and never under half the class's defence, set the odds; four rolls of 20, plus one, exchanges follow,
     * each costing one side a point, until they run out or either is down to the minimum. Returns {raid's, interceptor's}.
     */
    static int[] dogfight(GameConfig cfg, UnrestStep.R r, Plane a, UnitsCfg.PlaneClassCfg ac, Plane d, UnitsCfg.PlaneClassCfg dc) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        UnitsCfg.AirCombatCfg k = pc.airCombat();
        double base = ac.attackAt(a.tech()) > 0 ? ac.attackAt(a.tech()) : ac.defenseAt(a.tech());
        int att = Math.max((int) (base * a.efficiency() / 100), (int) (ac.defense() / 2));
        int def = Math.max((int) (dc.defenseAt(d.tech()) * d.efficiency() / 100), (int) (dc.defense() / 2));
        att += (int) (ac.stealthOr0() / 25);   // KNOWN ac_dog: stealth counts for each side
        def += (int) (dc.stealthOr0() / 25);
        if (att < 1) { def += 1 - att; att = 1; }
        if (def < 1) { att += 1 - def; def = 1; }
        double odds = Math.max(k.oddsFloor(), (double) att / (att + def));
        int intensity = k.intensityAdd();
        for (int i = 0; i < k.intensityDice(); i++) intensity += r.roll(k.intensityDie());
        int adam = 0, ddam = 0;
        for (int i = 0; i < intensity; i++) {
            if (a.efficiency() - adam < pc.minEfficiency() || d.efficiency() - ddam < pc.minEfficiency()) break;
            if (r.chance(odds)) ddam++; else adam++;
        }
        return new int[] {adam, ddam};
    }

    /** The hexes a flight crosses, field to target: each step the neighbour nearest the target, ties to the first direction. */
    static List<Coord> flightPath(World w, Coord from, Coord to) {
        List<Coord> path = new ArrayList<>(List.of(from));
        Coord cur = from;
        for (int guard = 0; !cur.equals(to) && guard < w.width() + w.height(); guard++) {
            Coord best = null;
            int bestD = Integer.MAX_VALUE;
            for (Coord n : Hex.neighbours(w, cur)) { int dd = Hex.distance(w, n, to); if (dd < bestD) { bestD = dd; best = n; } }
            if (best == null) break;
            cur = best;
            path.add(cur);
        }
        return path;
    }

    private static String article(String name) { return ("aeiou".indexOf(Character.toLowerCase(name.charAt(0))) >= 0 ? "an " : "a ") + name; }
}
