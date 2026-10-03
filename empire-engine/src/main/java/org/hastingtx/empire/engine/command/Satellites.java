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
import java.util.TreeMap;

/**
 * Satellites (issue #71; KNOWN laun.c launch_sat and launch_as, sate.c, satmap.c). A satellite is launched once into orbit
 * over a sector and stays up, circling the world or hanging geostationary over that one sector; {@code satellite} asks it
 * what it sees. An anti-sat is a missile that shoots one down, and rises against one being put up over its owner.
 */
final class Satellites {
    private Satellites() {}

    private static String q(double v) { return Ledger.q(v); }

    private static UnrestStep.R rng(GameConfig cfg, String key) { return new UnrestStep.R(Rng.stream(key, cfg.world() == null ? 0 : cfg.world().seed())); }

    /**
     * KNOWN launch_sat: within its range of its base, on the base's petrol; the booster may blow up on the pad, and it
     * may go a sector astray; the anti-sats of those at war with you may rise against it there; otherwise it is in
     * orbit, and reports from the next update.
     */
    static CommandResult launch(GameConfig cfg, Commodities com, World w, Country c, Plane p, UnitsCfg.PlaneClassCfg cls, Command.Launch l) {
        var mc = cfg.units().planes().missiles();
        if (p.orbiting()) return CommandResult.fail(w, cls.name() + " #" + p.id() + " is already in orbit");
        if (p.efficiency() < mc.minEfficiency()) return CommandResult.fail(w, cls.name() + " #" + p.id() + " is at " + q(p.efficiency()) + "%; it goes up at " + q(mc.minEfficiency()) + "% or better");
        String grounded = Air.grounded(cfg, w, p, c.id());
        if (grounded != null) return CommandResult.fail(w, grounded);
        Coord at = l.at();
        if (at == null || !w.inBounds(at)) return CommandResult.fail(w, "put it up over where? launch " + p.id() + " x,y");
        Air.Base base = Air.base(w, p);
        int dist = Hex.distance(w, base.at(), at);
        if (dist > cls.rangeAt(p.tech())) return CommandResult.fail(w, at + " is " + dist + " hexes off; a " + cls.name() + " goes up " + q(Math.floor(cls.rangeAt(p.tech()))) + " away at most");
        int pet = com.index("pet");
        if (base.stock().get(pet) < cls.fuel()) return CommandResult.fail(w, base.name() + " has " + q(base.stock().get(pet)) + " petrol; the booster takes " + q(cls.fuel()));

        World next = base.take(w, pet, cls.fuel());
        UnrestStep.R r = rng(cfg, "satellite:" + p.id() + ">" + at + ":" + w.updateNumber());
        String name = cls.name() + " #" + p.id();
        if (r.chance(mc.satPadFailBase() + (100 - p.efficiency()) / 100)) return new CommandResult(next.withoutPlane(p.id()), null, 0, name + ": the booster blew up on the pad");
        double i = p.tech() + p.efficiency();
        Coord over = at;
        String astray = "";
        if (r.chance(1 - i / (i + mc.offCourseScale()))) {
            Coord n = Hex.normalise(next, Hex.stepRaw(at, r.roll0(6)));
            if (n != null) { over = n; astray = "its trajectory was a little off; "; }
        }
        Missiles.Intercepted asat = Missiles.rise(cfg, r, next, c, "satellite", "anti-sat", cls.defenseAt(p.tech()), over, next.sector(over).owner());
        next = asat.world();
        String rose = asat.story().isEmpty() ? "" : asat.story() + "; ";
        if (asat.hit()) return new CommandResult(next.withoutPlane(p.id()), null, 0, name + " went up; " + astray + rose.substring(0, rose.length() - 2));
        next = next.withPlane(p.inOrbit(over, l.geo(), w.updateNumber()).withNote("put up over " + over));
        return new CommandResult(next, null, 0, name + " went up; " + astray + rose + "in " + (l.geo() ? "geostationary orbit" : "orbit")
                + " over " + over + ", reporting from the next update (satellite " + p.id() + ")");
    }

    /**
     * KNOWN launch_as: at a satellite in orbit within its range of the base — here, one of a country at war with you, over
     * a sector you can see (a satellite is told by where it is, as a ship you see is). It may blow up on the pad; one that
     * flies hits with pln_hitchance against the satellite's defence. Spent either way.
     */
    static CommandResult antiSat(GameConfig cfg, World w, Country c, Plane p, UnitsCfg.PlaneClassCfg cls, Command.Launch l) {
        var pc = cfg.units().planes();
        var mc = pc.missiles();
        if (p.efficiency() < mc.minEfficiency()) return CommandResult.fail(w, "missile #" + p.id() + " is at " + q(p.efficiency()) + "%; it launches at " + q(mc.minEfficiency()) + "% or better");
        String grounded = Air.grounded(cfg, w, p, c.id());
        if (grounded != null) return CommandResult.fail(w, grounded);
        Coord at = l.at();
        if (at == null || !w.inBounds(at)) return CommandResult.fail(w, "an " + cls.name() + " goes at a satellite: launch " + p.id() + " x,y at one over a sector you see");
        Set<Coord> seen = Visibility.of(w, cfg, c.id());
        Plane sat = !seen.contains(at) ? null : w.planes().stream()
                .filter(s -> s.orbiting() && s.at().equals(at) && s.owner() != c.id() && w.atWar(c.id(), s.owner()))
                .min(Comparator.comparingLong(Plane::id)).orElse(null);
        if (sat == null) return CommandResult.fail(w, "no enemy satellite in sight over " + at);
        Air.Base base = Air.base(w, p);
        int dist = Hex.distance(w, base.at(), at);
        if (dist > cls.rangeAt(p.tech())) return CommandResult.fail(w, at + " is " + dist + " hexes off; an " + cls.name() + " flies " + q(Math.floor(cls.rangeAt(p.tech()))));

        World next = w.withoutPlane(p.id());
        UnrestStep.R r = rng(cfg, "antisat:" + p.id() + ">" + sat.id() + ":" + w.updateNumber());
        String name = cls.name() + " #" + p.id();
        var scls = pc.planeClass(sat.cls());
        String theirs = next.country(sat.owner()).name() + "'s " + scls.name() + " #" + sat.id();
        if (Missiles.blowsUp(mc, r, p)) return new CommandResult(next, null, 0, name + " blew up on launch");
        if (!r.chance(Missiles.hitChance(mc, cls, p, scls.defenseAt(sat.tech()))))
            return new CommandResult(next, null, 0, name + " launched at " + theirs + " over " + at + ": a miss");
        return new CommandResult(next.withoutPlane(sat.id()), null, 0, name + " shot down " + theirs + " over " + at);
    }

    /**
     * KNOWN sate.c, satmap.c: what a satellite in orbit sees, within {@code report_range} × techfact × efficiency/100 of
     * it. Below 100% a share of the picture is lost to noise, the original's way. With imaging ("image") every sector goes
     * on your chart; without, only sea and mountains (the original's landsat marks the rest '?'). A spy satellite ("spy")
     * reports the foreign sectors under it (stock rounded), the ships (submarines only with imaging too, which also go
     * onto your contacts) and the land units.
     */
    static CommandResult report(GameConfig cfg, Commodities com, World w, Country c, Command.Satellite s) {
        var pc = cfg.units().planes();
        if (pc == null || pc.missiles() == null) return CommandResult.fail(w, "these rules have no satellites");
        var mc = pc.missiles();
        Plane p = w.plane(s.plane());
        if (p == null || p.owner() != c.id()) return CommandResult.fail(w, "no satellite #" + s.plane() + " of yours");
        UnitsCfg.PlaneClassCfg cls = pc.planeClass(p.cls());
        if (cls == null || !p.orbiting()) return CommandResult.fail(w, "plane #" + p.id() + " is not in orbit");
        long now = w.updateNumber();
        if (now <= p.launched()) return CommandResult.fail(w, cls.name() + " #" + p.id() + " went up this update; it reports from the next");
        int range = (int) Math.floor(mc.reportRange() * mc.techFactor(p.tech()) * p.efficiency() / 100);
        boolean spy = cls.has("spy"), image = cls.has("image");
        boolean[] noise = new boolean[100];
        int gap = (int) Math.ceil(100 - p.efficiency());
        for (int n = 0; n < gap; n++) noise[100 * n / gap] = true;
        double round = image ? mc.reportRoundImage() : mc.reportRound();

        // the sectors: onto your chart, and a spy's report of whose they are and what is there
        Set<Coord> under = Hex.within(w, p.at(), range);
        Map<Coord, SeenSector> charted = new TreeMap<>();
        List<String> sectors = new ArrayList<>();
        int crackle = 0;
        for (Coord at : under) {
            crackle = (crackle + 1) % 100;
            if (noise[crackle]) continue;
            Sector t = w.sector(at);
            if (spy && t.owned() && t.owner() != c.id())
                sectors.add(w.country(t.owner()).name() + "'s " + t.designation().replace('_', ' ') + " at " + at + " " + q(by(t.efficiency(), round / 2)) + "%"
                        + stock(com, t.stock(), round));
            // GUESS: sea and mountain, as they are; the original's map shows only their mnemonic
            if (image || !t.isLand() || t.terrain() == Terrain.MOUNTAIN)
                charted.put(at, new SeenSector(c.id(), at, t.terrain(), t.owner(), t.designation(), now));
        }
        List<SeenSector> seen = new ArrayList<>(w.seen());
        seen.removeIf(x -> x.owner() == c.id() && charted.containsKey(x.at()));
        seen.addAll(charted.values());
        World next = w.withSeen(seen);

        // the ships under it: a spy's report, and onto your contacts
        List<String> ships = new ArrayList<>();
        if (spy || image) {
            var sc = cfg.units().ships();
            List<Contact> contacts = new ArrayList<>(next.contacts());
            crackle = 0;
            for (Ship sh : w.ships()) {
                if (sh.owner() == c.id() || !under.contains(sh.at())) continue;
                if (sc != null && sc.shipClass(sh.cls()).submarine() && !(spy && image)) continue;
                crackle = (crackle + 1) % 100;
                if (noise[crackle]) continue;
                if (spy) ships.add(w.country(sh.owner()).name() + "'s " + (sc == null ? sh.cls() : sc.shipClass(sh.cls()).name()) + " #" + sh.id() + " at " + sh.at() + " " + q(Math.floor(sh.efficiency())) + "%");
                contacts.removeIf(k -> k.owner() == c.id() && k.shipId() == sh.id());
                contacts.add(new Contact(c.id(), sh.id(), sh.owner(), sh.cls(), sh.at(), now, 1.0));
            }
            contacts.sort(Comparator.comparingInt(Contact::owner).thenComparingLong(Contact::shipId));
            next = next.withContacts(contacts);
        }
        // the land units under it, a spy's report (KNOWN satmap: not spies, and each seen with chance eff/20)
        List<String> units = new ArrayList<>();
        var lc = cfg.units().land();
        if (spy && lc != null) {
            UnrestStep.R r = rng(cfg, "satellite-report:" + p.id() + ":" + now);
            crackle = 0;
            for (LandUnit u : w.units()) {
                if (u.owner() == c.id() || !under.contains(u.at())) continue;
                var ucls = lc.landClass(u.cls());
                if (ucls == null || ucls.has("spy") || !r.chance(u.efficiency() / 20)) continue;
                crackle = (crackle + 1) % 100;
                if (noise[crackle]) continue;
                units.add(w.country(u.owner()).name() + "'s " + ucls.name() + " #" + u.id() + " at " + u.at() + " " + q(Math.floor(u.efficiency())) + "%");
            }
        }

        StringBuilder out = new StringBuilder(cls.name() + " #" + p.id() + " over " + p.at() + ", " + q(Math.floor(p.efficiency())) + "%, sees " + range + " hexes"
                + (p.efficiency() < 100 ? " (some noise on the transmission)" : "") + ": " + charted.size() + " sectors charted");
        if (spy) {
            out.append(sectors.isEmpty() ? "; no foreign sectors" : "; " + sectors.size() + " foreign sectors: " + String.join("; ", sectors));
            out.append(ships.isEmpty() ? "; no ships" : "; ships: " + String.join("; ", ships));
            out.append(units.isEmpty() ? "; no units" : "; units: " + String.join("; ", units));
        } else if (image) out.append("; ships under it go on your contacts");
        return new CommandResult(next, null, 0, out.toString());
    }

    /** KNOWN roundintby: to the nearest multiple of {@code m}. */
    private static double by(double v, double m) { return m <= 0 ? v : Math.round(v / m) * m; }

    private static String stock(Commodities com, Stocks st, double round) {
        StringBuilder sb = new StringBuilder();
        for (String id : List.of("civ", "mil", "shell", "gun", "iron", "pet", "food")) {   // KNOWN satdisp_sect's columns
            if (!com.has(id)) continue;
            double v = by(st.get(com.index(id)), round);
            if (v > 0) sb.append(sb.isEmpty() ? ": " : ", ").append(id).append(' ').append(q(v));
        }
        return sb.toString();
    }
}
