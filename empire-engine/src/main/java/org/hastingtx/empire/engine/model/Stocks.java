package org.hastingtx.empire.engine.model;

import java.util.Arrays;

/**
 * Immutable per-sector commodity quantities. Index = Commodities position.
 *
 * <p>Held as {@code short} (issue #77). Empire's quantities are discrete, and a short covers the whole
 * range: the per-sector cap is 1000, and a sector that stores ten times (a city, a warehouse) tops out
 * at 10,000 — comfortably inside 32,767. That is why no units-of-ten packing is needed here; it was
 * only ever required to squeeze into ten bits.
 *
 * <p>The accessors stay wide, so the update steps do not change. 152 bytes a sector becomes 44.
 *
 * <p>An all-zero {@code Stocks} is shared rather than allocated. Most of a large world holds nothing —
 * ocean and wilderness — and the apply step rebuilds every sector's stock every update, so this is both
 * 64 bytes a sector of live heap and half a million allocations an update that no longer happen.
 */
public final class Stocks {
    private final short[] q;

    private Stocks(short[] q) { this.q = q; }

    private static final java.util.concurrent.ConcurrentHashMap<Integer, Stocks> ZERO = new java.util.concurrent.ConcurrentHashMap<>();

    private static Stocks canonical(short[] c) {
        for (short v : c) if (v != 0) return new Stocks(c);
        return zero(c.length);
    }

    static short narrow(double v) {
        long r = Math.round(v);
        if (r > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (r < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) r;
    }

    public static Stocks zero(int n) { return ZERO.computeIfAbsent(n, k -> new Stocks(new short[k])); }

    /** From whole units — the apply step's own arithmetic is integral, so nothing needs rounding. */
    public static Stocks of(int[] q) {
        short[] c = new short[q.length];
        for (int i = 0; i < q.length; i++) c[i] = q[i] > Short.MAX_VALUE ? Short.MAX_VALUE : q[i] < Short.MIN_VALUE ? Short.MIN_VALUE : (short) q[i];
        return canonical(c);
    }

    public static Stocks of(double[] q) {
        short[] c = new short[q.length];
        for (int i = 0; i < q.length; i++) c[i] = narrow(q[i]);
        return canonical(c);
    }

    public int size() { return q.length; }
    public double get(int i) { return q[i]; }
    public double[] toArray() { double[] out = new double[q.length]; for (int i = 0; i < q.length; i++) out[i] = q[i]; return out; }
    public double total() { double t = 0; for (short v : q) t += v; return t; }

    public Stocks with(int i, double v) { short[] c = q.clone(); c[i] = narrow(v); return canonical(c); }
    public Stocks plus(int i, double d) { short[] c = q.clone(); c[i] = narrow(q[i] + d); return canonical(c); }
    public Stocks plusAll(double[] d) {
        short[] c = q.clone();
        for (int i = 0; i < c.length; i++) c[i] = narrow(q[i] + d[i]);
        return canonical(c);
    }

    @Override public boolean equals(Object o) { return o instanceof Stocks s && Arrays.equals(q, s.q); }
    @Override public int hashCode() { return Arrays.hashCode(q); }
    @Override public String toString() { return Arrays.toString(q); }
}
