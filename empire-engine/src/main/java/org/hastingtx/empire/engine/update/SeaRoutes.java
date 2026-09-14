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

    /**
     * Hexes from every sector to the nearest harbour of {@code owner}, sailing over sea and that owner's
     * harbours — one breadth-first search from all the harbours at once. {@link Integer#MAX_VALUE} where
     * no harbour can be reached. Indexed like {@link World#sectors()}.
     */
    public static int[] harbourDistances(World w, GameConfig cfg, int owner) {
        int n = w.sectors().size();
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        ArrayDeque<Coord> q = new ArrayDeque<>();
        for (Sector s : w.ownedBy(owner)) if (isHarbor(cfg, s)) { dist[w.index(s.at())] = 0; q.add(s.at()); }
        while (!q.isEmpty()) {
            Coord c = q.poll();
            int d = dist[w.index(c)] + 1;
            for (Coord nb : Hex.neighbours(w, c)) {
                int i = w.index(nb);
                if (dist[i] <= d || !navigable(w, cfg, w.sector(nb), owner)) continue;
                dist[i] = d;
                q.add(nb);
            }
        }
        return dist;
    }

    /**
     * How far along {@code path} a ship may sail this time and still get back (Richard 2026-09-14,
     * ship #30): the largest {@code h ≤ hops} for which the hexes sailed plus the hexes from where she
     * stops to the nearest harbour, times fuel per hex and {@code reserve}, fit in her tank. 0 when she
     * may stay where she is but go no further; −1 when even where she is, she is already out of reach.
     */
    public static int safeHops(World w, List<Coord> path, int hops, int[] dist, double fuel, double perHex, double reserve) {
        for (int h = hops; h >= 0; h--) {
            int back = dist[w.index(path.get(h))];
            if (back == Integer.MAX_VALUE) continue;
            if ((h + back) * perHex * reserve <= fuel + 1e-9) return h;
        }
        return -1;
    }

    /**
     * The shortest sea route from {@code from} to whichever of the owner's harbours is nearest by water,
     * read straight off {@code dist} by always stepping to a neighbour one hex nearer; null when none is
     * reachable. The same measure the range check used, so a ship turned for home is sent where it said
     * she could get to (ship #33 in game 82 was sent to the harbour nearest as the crow flies, a hex
     * further by sea than her tank).
     */
    public static List<Coord> pathHome(World w, GameConfig cfg, int owner, Coord from, int[] dist) {
        int d = dist[w.index(from)];
        if (d == Integer.MAX_VALUE) return null;
        List<Coord> out = new ArrayList<>();
        out.add(from);
        Coord at = from;
        while (d > 0) {
            Coord next = null;
            for (Coord nb : Hex.neighbours(w, at))
                if (dist[w.index(nb)] == d - 1 && navigable(w, cfg, w.sector(nb), owner)) { next = nb; break; }
            if (next == null) return null;
            out.add(next);
            at = next;
            d--;
        }
        return out;
    }
}
