package org.hastingtx.empire.engine.geo;

import org.hastingtx.empire.engine.model.World;

import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every sector's six neighbours, flat: {@code table[i * 6 + d]} is a sector index, or -1 where the
 * world does not wrap and there is none (issue #87).
 *
 * <p>Cached across updates, because it is a property of the map's <em>shape</em> and nothing else.
 * Width, height and the two wrap flags fix it for the life of a game; the sectors can change owner,
 * stock and designation all they like without moving. It was being rebuilt from scratch every update —
 * at 512x1024 that is 3.1 million entries and, before the arithmetic was written out, sixteen million
 * short-lived objects.
 *
 * <p>Held through a {@link SoftReference} so a long-lived server hosting games of several different
 * shapes can give the memory back under pressure rather than pinning 12 MB a shape forever.
 */
public final class NeighbourTable {
    private NeighbourTable() {}

    private record Shape(int width, int height, boolean wrapX, boolean wrapY) {}

    private static final Map<Shape, SoftReference<int[]>> CACHE = new ConcurrentHashMap<>();

    public static int[] of(World w) {
        Shape shape = new Shape(w.width(), w.height(), w.wrapX(), w.wrapY());
        SoftReference<int[]> ref = CACHE.get(shape);
        int[] cached = ref == null ? null : ref.get();
        if (cached != null) return cached;
        int[] built = build(w);
        CACHE.put(shape, new SoftReference<>(built));
        return built;
    }

    private static int[] build(World w) {
        int n = w.width() * w.height();
        int[] t = new int[n * 6];
        for (int y = 0, i = 0; y < w.height(); y++)
            for (int x = 0; x < w.width(); x++, i++)
                for (int d = 0; d < 6; d++)
                    t[i * 6 + d] = Hex.neighbourIndex(w, x, y, d);
        return t;
    }
}
