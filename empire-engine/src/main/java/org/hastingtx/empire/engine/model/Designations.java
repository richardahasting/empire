package org.hastingtx.empire.engine.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A byte id for every designation string a sector has ever worn (issue #77).
 *
 * <p>A sector's designation is one of about thirty strings from {@code config/schema.yaml}, and every
 * sector in the world holds a reference to one of them. Storing the id instead of the reference costs
 * a byte rather than a pointer, and — more to the point — {@link Sector} then has no reference field
 * to chase for it.
 *
 * <p>The table is append-only and global rather than per-config, deliberately: two games may run side
 * by side under different configs, and an id must mean the same string in both. Thirty-odd entries is
 * the practical ceiling; the cap is 127 so the id always fits a signed byte.
 */
public final class Designations {
    private Designations() {}

    public static final int MAX = 127;

    private static final Map<String, Byte> IDS = new ConcurrentHashMap<>();
    private static volatile String[] names = new String[0];

    /** The id for {@code d}, registering it on first sight. */
    public static byte id(String d) {
        Byte b = IDS.get(d);
        if (b != null) return b;
        return register(d);
    }

    private static synchronized byte register(String d) {
        Byte b = IDS.get(d);
        if (b != null) return b;
        String[] cur = names;
        if (cur.length >= MAX) throw new IllegalStateException("more than " + MAX + " distinct designations: " + d);
        String[] next = java.util.Arrays.copyOf(cur, cur.length + 1);
        next[cur.length] = d;
        byte id = (byte) cur.length;
        names = next;
        IDS.put(d, id);
        return id;
    }

    /** The string for {@code id}. */
    public static String name(byte id) {
        String[] cur = names;
        if (id < 0 || id >= cur.length) throw new IllegalArgumentException("no designation with id " + id);
        return cur[id];
    }

    /** How many distinct designations have been seen. Diagnostics and tests. */
    public static int count() { return names.length; }
}
