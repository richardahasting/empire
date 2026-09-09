package org.hastingtx.empire.engine.model;

import java.util.Arrays;

/**
 * A sector's standing delivery orders, one per commodity (KNOWN: the original's deliver.c).
 * Above {@code threshold[c]}, whatever is there moves one hex in direction {@code dir[c]} at the
 * update; {@code NONE} means no order. Directions index {@link org.hastingtx.empire.engine.geo.Hex#DIR_NAMES}.
 */
public record DeliverOrders(int[] dir, double[] threshold) {
    public static final int NONE = -1;

    public static DeliverOrders none(int n) { int[] d = new int[n]; Arrays.fill(d, NONE); return new DeliverOrders(d, new double[n]); }

    public boolean has(int c) { return dir[c] != NONE; }
    public int dir(int c) { return dir[c]; }
    public double threshold(int c) { return threshold[c]; }
    public int count() { int k = 0; for (int d : dir) if (d != NONE) k++; return k; }

    public DeliverOrders with(int c, int d, double thr) {
        int[] nd = dir.clone(); double[] nt = threshold.clone();
        nd[c] = d; nt[c] = d == NONE ? 0 : thr;
        return new DeliverOrders(nd, nt);
    }
    public DeliverOrders without(int c) { return with(c, NONE, 0); }

    /** Every direction turned k × 60° counter-clockwise — for rotated fixtures. */
    public DeliverOrders rotated(int k) {
        int[] nd = dir.clone();
        for (int i = 0; i < nd.length; i++) if (nd[i] != NONE) nd[i] = Math.floorMod(nd[i] + k, 6);
        return new DeliverOrders(nd, threshold.clone());
    }

    @Override public boolean equals(Object o) { return o instanceof DeliverOrders d && Arrays.equals(dir, d.dir) && Arrays.equals(threshold, d.threshold); }
    @Override public int hashCode() { return 31 * Arrays.hashCode(dir) + Arrays.hashCode(threshold); }
    @Override public String toString() { return "DeliverOrders" + Arrays.toString(dir) + Arrays.toString(threshold); }
}
