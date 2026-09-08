package org.hastingtx.empire.engine.model;

import java.util.Arrays;

/** Immutable per-sector commodity quantities. Index = Commodities position. */
public final class Stocks {
    private final double[] q;

    private Stocks(double[] q) { this.q = q; }

    public static Stocks zero(int n) { return new Stocks(new double[n]); }
    public static Stocks of(double[] q) { return new Stocks(q.clone()); }

    public int size() { return q.length; }
    public double get(int i) { return q[i]; }
    public double[] toArray() { return q.clone(); }
    public double total() { double t = 0; for (double v : q) t += v; return t; }

    public Stocks with(int i, double v) { double[] c = q.clone(); c[i] = v; return new Stocks(c); }
    public Stocks plus(int i, double d) { double[] c = q.clone(); c[i] += d; return new Stocks(c); }
    public Stocks plusAll(double[] d) {
        double[] c = q.clone();
        for (int i = 0; i < c.length; i++) c[i] += d[i];
        return new Stocks(c);
    }

    @Override public boolean equals(Object o) { return o instanceof Stocks s && Arrays.equals(q, s.q); }
    @Override public int hashCode() { return Arrays.hashCode(q); }
    @Override public String toString() { return Arrays.toString(q); }
}
