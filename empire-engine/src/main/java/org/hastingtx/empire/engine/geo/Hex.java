package org.hastingtx.empire.engine.geo;

import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Odd-r offset hex grid. Cube coordinates are used for distance and rotation.
 * Direction order is fixed and documented: 0=E, 1=NE, 2=NW, 3=W, 4=SW, 5=SE.
 */
public final class Hex {
    private Hex() {}

    /** Cube direction vectors (q, r, s), index = direction. */
    private static final int[][] DIRS = { {1, 0, -1}, {1, -1, 0}, {0, -1, 1}, {-1, 0, 1}, {-1, 1, 0}, {0, 1, -1} };

    public record Cube(int q, int r, int s) {
        public Cube plus(Cube o) { return new Cube(q + o.q, r + o.r, s + o.s); }
        public Cube minus(Cube o) { return new Cube(q - o.q, r - o.r, s - o.s); }
        /** Rotate 60 degrees counter-clockwise about the origin. */
        public Cube rot60() { return new Cube(-s, -q, -r); }
        public int length() { return (Math.abs(q) + Math.abs(r) + Math.abs(s)) / 2; }
    }

    public static Cube toCube(Coord c) {
        int q = c.x() - (c.y() - (c.y() & 1)) / 2;
        int r = c.y();
        return new Cube(q, r, -q - r);
    }

    public static Coord toOffset(Cube c) {
        int x = c.q() + (c.r() - (c.r() & 1)) / 2;
        return new Coord(x, c.r());
    }

    public static Cube dir(int d) { int[] v = DIRS[d]; return new Cube(v[0], v[1], v[2]); }

    /** Direction names in index order, and the original's keys (KNOWN: "juygbn" — j east, u north-east, y north-west, g west, b south-west, n south-east). */
    public static final String[] DIR_NAMES = {"e", "ne", "nw", "w", "sw", "se"};
    private static final String ORIGINAL_KEYS = "juygbn";

    public static String dirName(int d) { return DIR_NAMES[d]; }

    /** 0..5 for a name ("ne", "north-east", "NE") or an original key ("u"); -1 if neither. */
    public static int parseDir(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("north", "n").replace("south", "s").replace("east", "e").replace("west", "w");
        for (int d = 0; d < 6; d++) if (DIR_NAMES[d].equals(t)) return d;
        if (t.length() == 1) { int k = ORIGINAL_KEYS.indexOf(t.charAt(0)); if (k >= 0) return k; }
        return -1;
    }

    /** Unwrapped neighbour in direction d (may be out of bounds). */
    public static Coord stepRaw(Coord c, int d) { return toOffset(toCube(c).plus(dir(d))); }

    /** Walk n steps in direction d, unwrapped. */
    public static Coord stepRaw(Coord c, int d, int n) {
        Cube cube = toCube(c);
        Cube v = dir(d);
        for (int i = 0; i < n; i++) cube = cube.plus(v);
        return toOffset(cube);
    }

    /** Normalise into the world, applying wrap; null if out of bounds and not wrapped. */
    public static Coord normalise(World w, Coord c) {
        int x = c.x(), y = c.y();
        if (w.wrapY()) y = Math.floorMod(y, w.height());
        else if (y < 0 || y >= w.height()) return null;
        if (w.wrapX()) x = Math.floorMod(x, w.width());
        else if (x < 0 || x >= w.width()) return null;
        return new Coord(x, y);
    }

    /** The up-to-six in-world neighbours, in direction order. */
    public static List<Coord> neighbours(World w, Coord c) {
        List<Coord> out = new ArrayList<>(6);
        for (int d = 0; d < 6; d++) {
            Coord n = normalise(w, stepRaw(c, d));
            if (n != null) out.add(n);
        }
        return out;
    }

    /** Hex distance ignoring wrap. */
    public static int distanceRaw(Coord a, Coord b) { return toCube(a).minus(toCube(b)).length(); }

    /** Hex distance honouring wrap: the minimum over image offsets. Requires even height when wrapY. */
    public static int distance(World w, Coord a, Coord b) {
        int best = Integer.MAX_VALUE;
        int[] dxs = w.wrapX() ? new int[] {-w.width(), 0, w.width()} : new int[] {0};
        int[] dys = w.wrapY() ? new int[] {-w.height(), 0, w.height()} : new int[] {0};
        for (int dx : dxs) for (int dy : dys) {
            best = Math.min(best, distanceRaw(a, new Coord(b.x() + dx, b.y() + dy)));
        }
        return best;
    }

    /** Rotate a coordinate 60° * k counter-clockwise about {@code center} (unwrapped). */
    public static Coord rotate(Coord c, Coord center, int k) {
        Cube o = toCube(center);
        Cube v = toCube(c).minus(o);
        for (int i = 0; i < Math.floorMod(k, 6); i++) v = v.rot60();
        return toOffset(v.plus(o));
    }
}
