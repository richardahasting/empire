package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.Terrain;
import org.hastingtx.empire.engine.model.World;

import java.util.*;

/** Where a ship may go: sea hexes, and harbours of its owner. Shortest path by hops (issue #56). */
public final class SeaRoutes {
    private SeaRoutes() {}

    public static boolean isHarbor(GameConfig cfg, Sector s) { return s.owned() && cfg.sectorType(s.designation()).hasFlag("builds_ships"); }

    public static boolean navigable(World w, GameConfig cfg, Sector s, int owner) {
        return s.terrain() == Terrain.OCEAN || (s.owner() == owner && isHarbor(cfg, s));
    }

    /** Path from..to inclusive over navigable hexes, or null. A ship may leave any hex it is on. */
    public static List<Coord> path(World w, GameConfig cfg, int owner, Coord from, Coord to) {
        if (from.equals(to)) return List.of(from);
        if (!navigable(w, cfg, w.sector(to), owner)) return null;
        Map<Coord, Coord> prev = new HashMap<>();
        ArrayDeque<Coord> q = new ArrayDeque<>();
        q.add(from); prev.put(from, from);
        while (!q.isEmpty()) {
            Coord c = q.poll();
            for (Coord n : Hex.neighbours(w, c)) {
                if (prev.containsKey(n) || !navigable(w, cfg, w.sector(n), owner)) continue;
                prev.put(n, c);
                if (n.equals(to)) {
                    LinkedList<Coord> out = new LinkedList<>();
                    for (Coord x = to; !x.equals(from); x = prev.get(x)) out.addFirst(x);
                    out.addFirst(from);
                    return out;
                }
                q.add(n);
            }
        }
        return null;
    }
}
