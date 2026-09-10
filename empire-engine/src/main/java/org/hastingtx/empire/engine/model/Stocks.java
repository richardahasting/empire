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
 */
public final class Stocks {
    private final short[] q;

    private Stocks(short[] q) { this.q = q; }

    static short narrow(double v) {
        long r = Math.round(v);
        if (r > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (r < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) r;
    }

    public static Stocks zero(int n) { return new Stocks(new short[n]); }

    public static Stocks of(double[] q) {
        short[] c = new short[q.length];
        for (int i = 0; i < q.length; i++) c[i] = narrow(q[i]);
        return new Stocks(c);
    }

    public int size() { return q.length; }
    public double get(int i) { return q[i]; }
    public double[] toArray() { double[] out = new double[q.length]; for (int i = 0; i < q.length; i++) out[i] = q[i]; return out; }
    public double total() { double t = 0; for (short v : q) t += v; return t; }

    public Stocks with(int i, double v) { short[] c = q.clone(); c[i] = narrow(v); return new Stocks(c); }
    public Stocks plus(int i, double d) { short[] c = q.clone(); c[i] = narrow(q[i] + d); return new Stocks(c); }
    public Stocks plusAll(double[] d) {
        short[] c = q.clone();
        for (int i = 0; i < c.length; i++) c[i] = narrow(q[i] + d[i]);
        return new Stocks(c);
    }

    @Override public boolean equals(Object o) { return o instanceof Stocks s && Arrays.equals(q, s.q); }
    @Override public int hashCode() { return Arrays.hashCode(q); }
    @Override public String toString() { return Arrays.toString(q); }
}
