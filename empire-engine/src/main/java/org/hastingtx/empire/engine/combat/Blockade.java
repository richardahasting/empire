package org.hastingtx.empire.engine.combat;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;

import java.util.*;

/**
 * A blockade (issue #68): a warship on station stops a hostile ship that comes within
 * {@code missions.blockade_radius} of her, and holds one that is already there. Hostile is exactly
 * what makes her guns fire — at war, or marked for a peacetime shot — so a blockade is never an act of
 * war by itself: it is the war, at a place.
 *
 * <p>Read from the ships as they stood before anyone moved this update (or, for an order given now,
 * as they stand now), so it does not matter which ship is processed first.
 */
public final class Blockade {
    private Blockade() {}

    /** How far along {@code path} the mover may go, up to {@code hops}, and who stopped her if anyone did. */
    public record Limit(int hops, Ship by) {}

    public static Limit limit(World w, GameConfig cfg, Ship mover, List<Coord> path, int hops, long now) {
        UnitsCfg.ShipsCfg sc = cfg.units() == null ? null : cfg.units().ships();
        if (sc == null || sc.combat() == null || hops <= 0) return new Limit(hops, null);
        int radius = sc.missionsOrDefault().blockadeRadiusOr0();
        List<Ship> guards = new ArrayList<>();
        for (Ship b : w.ships()) {
            if (!Ship.BLOCKADE.equals(b.mission()) || b.station() == null || !b.at().equals(b.station())) continue;
            if (!sc.shipClass(b.cls()).armed() || b.efficiency() <= sc.combat().sinkAt()) continue;
            if (Gunnery.hostile(w, b.owner(), mover, now)) guards.add(b);
        }
        if (guards.isEmpty()) return new Limit(hops, null);
        for (int h = 0; h <= hops; h++) {
            for (Ship b : guards)
                if (Hex.distance(w, path.get(h), b.at()) <= radius) return new Limit(h, b);
        }
        return new Limit(hops, null);
    }

    /** Every hex within {@code r} of {@code c}, walking neighbours so a wrapping world is handled. */
    public static Set<Coord> within(World w, Coord c, int r) {
        Set<Coord> seen = new TreeSet<>();
        seen.add(c);
        List<Coord> frontier = List.of(c);
        for (int k = 0; k < r; k++) {
            List<Coord> next = new ArrayList<>();
            for (Coord f : frontier) for (Coord n : Hex.neighbours(w, f)) if (seen.add(n)) next.add(n);
            frontier = next;
        }
        return seen;
    }
}
