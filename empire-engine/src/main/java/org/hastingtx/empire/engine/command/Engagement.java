package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.combat.Gunnery;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Rng;

import java.util.*;

/**
 * {@code fire SHIP x,y [CLASS]} (issue #68). It happens now, as a sail does (Richard 2026-09-13), and
 * the exchange is complete before the order returns: her salvo lands, then the target and everything
 * of its country in reach of her — ships, forts and harbours — answer together.
 *
 * <p>Firing on a country you are at peace with is allowed and declares nothing (Richard, #137: "it
 * will not be an automatic thing"). It marks the ship that did it: for
 * {@code combat.fired_upon_updates} that hull is fair game to the country it fired on, and its
 * warships and coastal guns will engage her on sight at the update. The rest of her navy is not.
 *
 * <p>The target must be something you can see — a contact fresh enough to aim at, standing where you
 * saw it. A contact is where a ship was, not where it is; if she has sailed, there is nothing to hit.
 */
public final class Engagement {
    private Engagement() {}

    public static CommandResult fire(GameConfig cfg, Commodities com, World w, Country c, Command.Fire f) {
        UnitsCfg.ShipsCfg sc = cfg.units() == null ? null : cfg.units().ships();
        UnitsCfg.CombatCfg cc = sc == null ? null : sc.combat();
        if (cc == null) return CommandResult.fail(w, "this world has no rules for combat");
        if (c.inSanctuary()) return CommandResult.fail(w, "break sanctuary before you fire on anyone");
        Ship ship = w.ship(f.ship());
        if (ship == null || ship.owner() != c.id()) return CommandResult.fail(w, "no ship #" + f.ship() + " of yours");
        UnitsCfg.ShipClassCfg cls = sc.shipClass(ship.cls());
        if (!cls.armed()) return CommandResult.fail(w, "a " + cls.name() + " has no guns");
        Gunnery.Battery mine = Gunnery.of(sc, com, ship, c.name());
        if (mine == null) {
            if (ship.efficiency() <= cc.sinkAt()) return CommandResult.fail(w, "ship #" + ship.id() + " is barely afloat");
            if (sc.crews() && ship.crew() < cls.crewOr0()) return CommandResult.fail(w, "ship #" + ship.id() + " is short-handed: nobody to man the guns");
            if (ship.stock().get(com.index("gun")) < 1) return CommandResult.fail(w, "ship #" + ship.id() + " has no guns aboard; rearm her in harbour");
            return CommandResult.fail(w, "ship #" + ship.id() + " has no shells; rearm her in harbour");
        }
        if (f.at() == null || !w.inBounds(f.at())) return CommandResult.fail(w, "fire at where?");

        long now = w.updateNumber();
        Ship target = null;
        boolean sawSubOnly = false;
        for (Ship t : w.ships()) {
            if (t.owner() == c.id() || !t.at().equals(f.at())) continue;
            UnitsCfg.ShipClassCfg tc = sc.shipClass(t.cls());
            if (f.cls() != null && !f.cls().isBlank() && !tc.id().equalsIgnoreCase(f.cls()) && !tc.name().equalsIgnoreCase(f.cls())) continue;
            if (!sees(w, cc, mine, c.id(), t, tc, now)) continue;
            if (!Gunnery.canHit(mine, tc)) { sawSubOnly = true; continue; }
            if (target == null || t.id() < target.id()) target = t;
        }
        if (target == null)
            return CommandResult.fail(w, sawSubOnly ? "that is a submarine, and a " + cls.name() + " cannot hunt one"
                    : "nothing of anyone else's that you can see at " + f.at() + (f.cls() == null ? "" : " matching " + f.cls()));
        Country them = w.country(target.owner());
        if (them.inSanctuary()) return CommandResult.fail(w, them.name() + " is in sanctuary and cannot be touched");
        int dist = Hex.distance(w, ship.at(), target.at());
        if (dist > mine.range()) return CommandResult.fail(w, f.at() + " is " + dist + " hexes away; a " + cls.name() + " reaches " + mine.range());

        UnitsCfg.ShipClassCfg tc = sc.shipClass(target.cls());
        StringBuilder story = new StringBuilder();
        Map<Long, Double> hurt = new TreeMap<>();
        Map<Long, Double> shellsOut = new TreeMap<>();
        Map<Coord, Double> sectorShellsOut = new TreeMap<>();

        // her salvo
        double guns = Gunnery.gunsFired(cc, mine);
        double dmg = Gunnery.damage(cc, mine, guns, tc.armorOr0(), Gunnery.roll(cc, Rng.stream("fire:" + ship.id() + ">" + target.id() + ":" + now + ":" + (long) mine.shells(), seed(cfg))));
        hurt.merge(target.id(), dmg, Double::sum);
        shellsOut.merge(ship.id(), Gunnery.shellsFor(cc, guns), Double::sum);
        story.append(label(sc, ship)).append(" fired ").append((long) guns).append(guns == 1 ? " gun" : " guns").append(" at ").append(them.name()).append("'s ").append(tc.name())
             .append(" at ").append(target.at()).append(": hit for ").append(fmt(dmg)).append('%');

        // the answer: everything of theirs that can reach her and hurt her, from before her salvo landed
        List<String> answers = new ArrayList<>();
        double back = 0;
        for (Ship r : w.ships()) {
            if (r.owner() != target.owner()) continue;
            Gunnery.Battery b = Gunnery.of(sc, com, r, them.name());
            if (b == null || !Gunnery.canHit(b, cls) || !Gunnery.inRange(w, b, ship.at())) continue;
            double g = Gunnery.gunsFired(cc, b);
            if (g < 1) continue;
            double d = Gunnery.damage(cc, b, g, cls.armorOr0(), Gunnery.roll(cc, Rng.stream("fire:" + r.id() + ">" + ship.id() + ":" + now + ":" + (long) b.shells(), seed(cfg))));
            back += d;
            shellsOut.merge(r.id(), Gunnery.shellsFor(cc, g), Double::sum);
            answers.add(b.label() + " for " + fmt(d) + "%");
        }
        if (!cls.submarine()) for (Sector s : w.ownedBy(target.owner())) {
            Gunnery.Battery b = Gunnery.of(cc, com, s, s.stock(), them.levels().tech(), them.name());
            if (b == null || !Gunnery.inRange(w, b, ship.at())) continue;
            double g = Gunnery.gunsFired(cc, b);
            if (g < 1) continue;
            double d = Gunnery.damage(cc, b, g, cls.armorOr0(), Gunnery.roll(cc, Rng.stream("fire:" + s.at() + ">" + ship.id() + ":" + now + ":" + (long) b.shells(), seed(cfg))));
            back += d;
            sectorShellsOut.merge(s.at(), Gunnery.shellsFor(cc, g), Double::sum);
            answers.add(b.label() + " for " + fmt(d) + "%");
        }
        if (back > 0) hurt.merge(ship.id(), back, Double::sum);

        // apply it all at once
        int shell = com.index("shell");
        List<Ship> ships = new ArrayList<>();
        for (Ship s : w.ships()) {
            Double sp = shellsOut.get(s.id());
            if (sp != null) s = s.withStock(s.stock().plus(shell, -sp));
            Double h = hurt.get(s.id());
            if (h != null) s = s.withEfficiency(Math.max(0, s.efficiency() - h));
            ships.add(s);
        }
        World next = w.withShips(ships);
        for (var e : sectorShellsOut.entrySet()) {
            Sector s = next.sector(e.getKey());
            next = next.withSector(s.withStock(s.stock().plus(shell, -e.getValue())));
        }
        Ship afterMe = next.ship(ship.id()), afterThem = next.ship(target.id());
        if (!answers.isEmpty()) story.append(". ").append(them.name()).append(" answered: ").append(String.join(", ", answers));

        // a shot fired in peacetime marks the ship that fired it
        if (!w.atWar(c.id(), them.id()) && cc.grudgeUpdates() > 0) {
            Map<Integer, Long> marks = new TreeMap<>(afterMe.firedOn());
            marks.put(them.id(), now + cc.grudgeUpdates());
            afterMe = afterMe.withFiredOn(marks);
            next = next.withShip(afterMe);
            story.append(". You are not at war: for ").append(cc.grudgeUpdates()).append(" updates ").append(them.name()).append(" may fire on her on sight");
        }
        // they know who did it
        List<Contact> contacts = new ArrayList<>();
        for (Contact k : next.contacts()) if (!(k.owner() == them.id() && k.shipId() == ship.id())) contacts.add(k);
        contacts.add(new Contact(them.id(), ship.id(), c.id(), ship.cls(), ship.at(), now, 1.0));
        contacts.sort(Comparator.comparingInt(Contact::owner).thenComparingLong(Contact::shipId));
        next = next.withContacts(contacts);

        String hitLine = "fired on by " + label(sc, ship) + " of " + c.name() + " for " + fmt(dmg) + "%";
        next = next.withShip(afterThem.withNote(append(afterThem.note(), hitLine)));
        afterThem = next.ship(target.id());
        next = next.withShip(afterMe.withNote(append(afterMe.note(), "fired on " + them.name() + "'s " + tc.name() + " at " + target.at() + (back > 0 ? "; took " + fmt(back) + "% in return" : ""))));
        afterMe = next.ship(ship.id());

        // the drowned
        for (Ship gone : List.of(afterThem, afterMe)) {
            if (gone.efficiency() > cc.sinkAt()) continue;
            int victor = gone.owner() == c.id() ? them.id() : c.id();
            next = sink(cfg, sc, com, next, gone, victor);
            story.append(". ").append(gone.owner() == c.id() ? "Your " + cls.name() + " went down" : them.name() + "'s " + tc.name() + " went down");
        }
        if (next.ship(ship.id()) != null) story.append(". She is at ").append(fmt(next.ship(ship.id()).efficiency())).append('%');
        return new CommandResult(next, null, 0, story.toString());
    }

    /** What she can aim at: a contact fresh enough and standing where it was seen, or a surface ship in her own sight. */
    static boolean sees(World w, UnitsCfg.CombatCfg cc, Gunnery.Battery b, int country, Ship t, UnitsCfg.ShipClassCfg tc, long now) {
        for (Contact k : w.contacts())
            if (k.owner() == country && k.shipId() == t.id() && k.at().equals(t.at()) && k.age(now) <= cc.maxTargetAge()) return true;
        return !tc.submarine() && Hex.distance(w, b.at(), t.at()) <= b.sight();
    }

    /** Gone, with her cargo shared among the victor's ships in her hex and the rest lost. */
    static World sink(GameConfig cfg, UnitsCfg.ShipsCfg sc, Commodities com, World w, Ship s, int victor) {
        List<Ship> near = new ArrayList<>();
        for (Ship v : w.ships()) if (v.owner() == victor && v.id() != s.id() && v.at().equals(s.at()) && v.efficiency() > sc.combat().sinkAt()) near.add(v);
        near.sort(Comparator.comparingLong(Ship::id));
        Gunnery.Salvage sal = Gunnery.salvage(sc, cfg.capture(), com, s, near);
        World next = w;
        for (Ship v : sal.victors()) next = next.withShip(v);
        next = next.withoutShip(s.id());
        List<Contact> contacts = new ArrayList<>(next.contacts());
        contacts.removeIf(k -> k.shipId() == s.id());
        return next.withContacts(contacts);
    }

    private static long seed(GameConfig cfg) { return cfg.world() == null ? 0 : cfg.world().seed(); }
    private static String label(UnitsCfg.ShipsCfg sc, Ship s) { return sc.shipClass(s.cls()).name() + " #" + s.id(); }
    private static String append(String note, String line) { return note == null || note.isBlank() ? line : note + "; " + line; }
    private static String fmt(double v) { return org.hastingtx.empire.engine.update.Ledger.q(v); }
}
