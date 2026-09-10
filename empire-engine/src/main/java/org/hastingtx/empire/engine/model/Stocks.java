package org.hastingtx.empire.engine.model;

import java.util.Arrays;

/**
 * Immutable per-sector commodity quantities. Index = Commodities position.
 *
 * <p>Held as {@code int} (issues #77, #91). Empire's quantities are discrete, so they are not doubles;
 * they are not shorts either, because the per-sector cap is 10,000 and a warehouse or a city stores ten
 * times that. 100,000 does not fit in 32,767, and a silent clamp there would not stay silent — the
 * apply step computes the new stock as an {@code int}, so the clamp would show up as the conservation
 * check finding the books short and throwing. The original packed quantities into ten bits; we are not
 * packing, so the cap is a rule rather than a consequence of the storage.
 *
 * <p>The accessors stay wide, so the update steps do not change. 152 bytes a sector becomes 72.
 *
 * <p>An all-zero {@code Stocks} is shared rather than allocated. Most of a large world holds nothing —
 * ocean and wilderness — and the apply step rebuilds every sector's stock every update, so this is both
 * 64 bytes a sector of live heap and half a million allocations an update that no longer happen.
 */
public final class Stocks {
    private final int[] q;

    private Stocks(int[] q) { this.q = q; }

    private static final java.util.concurrent.ConcurrentHashMap<Integer, Stocks> ZERO = new java.util.concurrent.ConcurrentHashMap<>();

    private static Stocks canonical(int[] c) {
        for (int v : c) if (v != 0) return new Stocks(c);
        return zero(c.length);
    }

    static int narrow(double v) {
        long r = Math.round(v);
        if (r > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (r < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) r;
    }

    public static Stocks zero(int n) { return ZERO.computeIfAbsent(n, k -> new Stocks(new int[k])); }

    /** From whole units — the apply step's own arithmetic is integral, so nothing needs rounding. */
    public static Stocks of(int[] q) { return canonical(q.clone()); }

    public static Stocks of(double[] q) {
        int[] c = new int[q.length];
        for (int i = 0; i < q.length; i++) c[i] = narrow(q[i]);
        return canonical(c);
    }

    public int size() { return q.length; }
    public double get(int i) { return q[i]; }
    public double[] toArray() { double[] out = new double[q.length]; for (int i = 0; i < q.length; i++) out[i] = q[i]; return out; }
    public double total() { double t = 0; for (int v : q) t += v; return t; }

    public Stocks with(int i, double v) { int[] c = q.clone(); c[i] = narrow(v); return canonical(c); }
    public Stocks plus(int i, double d) { int[] c = q.clone(); c[i] = narrow(q[i] + d); return canonical(c); }
    public Stocks plusAll(double[] d) {
        int[] c = q.clone();
        for (int i = 0; i < c.length; i++) c[i] = narrow(q[i] + d[i]);
        return canonical(c);
    }

    @Override public boolean equals(Object o) { return o instanceof Stocks s && Arrays.equals(q, s.q); }
    @Override public int hashCode() { return Arrays.hashCode(q); }
    @Override public String toString() { return Arrays.toString(q); }
}
