package org.hastingtx.empire.engine.model;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A sector's standing delivery orders, one per commodity (KNOWN: the original's deliver.c).
 * Above {@code threshold[c]}, whatever is there moves one hex in direction {@code dir[c]} at the
 * update; {@code NONE} means no order. Directions index {@link org.hastingtx.empire.engine.geo.Hex#DIR_NAMES}.
 *
 * <p>Held narrow (issue #77): a direction is one of six, so a byte; a threshold is a quantity, so an
 * int, exact — byte scaling would make {@code deliver food 100} mean something else, and a short would
 * stop short of a warehouse's 100,000 (issue #91). 228 bytes a sector becomes about 150, and a sector with no orders at all — most of a large world — shares the
 * canonical empty instance and costs only the reference.
 */
public final class DeliverOrders {
    public static final int NONE = -1;

    private final byte[] dir;
    private final int[] threshold;

    private DeliverOrders(byte[] dir, int[] threshold) { this.dir = dir; this.threshold = threshold; }

    private static final ConcurrentHashMap<Integer, DeliverOrders> EMPTY = new ConcurrentHashMap<>();

    /** No orders at all. Shared: the instance is immutable and every empty sector wants the same one. */
    public static DeliverOrders none(int n) {
        return EMPTY.computeIfAbsent(n, k -> {
            byte[] d = new byte[k];
            Arrays.fill(d, (byte) NONE);
            return new DeliverOrders(d, new int[k]);
        });
    }

    public int size() { return dir.length; }
    public boolean has(int c) { return dir[c] != NONE; }
    public int dir(int c) { return dir[c]; }
    public double threshold(int c) { return threshold[c]; }
    public int count() { int k = 0; for (byte d : dir) if (d != NONE) k++; return k; }
    /** True when nothing is ordered anywhere — lets callers skip a sector without scanning it twice. */
    public boolean isEmpty() { return this == none(dir.length) || count() == 0; }

    public DeliverOrders with(int c, int d, double thr) {
        byte[] nd = dir.clone();
        int[] nt = threshold.clone();
        nd[c] = (byte) d;
        nt[c] = d == NONE ? 0 : Sector.thresholdOf(thr);
        for (byte v : nd) if (v != NONE) return new DeliverOrders(nd, nt);
        return none(dir.length);
    }

    public DeliverOrders without(int c) { return with(c, NONE, 0); }

    /** Every direction turned k × 60° counter-clockwise — for rotated fixtures. */
    public DeliverOrders rotated(int k) {
        byte[] nd = dir.clone();
        boolean any = false;
        for (int i = 0; i < nd.length; i++) if (nd[i] != NONE) { nd[i] = (byte) Math.floorMod(nd[i] + k, 6); any = true; }
        return any ? new DeliverOrders(nd, threshold.clone()) : none(dir.length);
    }

    @Override public boolean equals(Object o) { return o instanceof DeliverOrders d && Arrays.equals(dir, d.dir) && Arrays.equals(threshold, d.threshold); }
    @Override public int hashCode() { return 31 * Arrays.hashCode(dir) + Arrays.hashCode(threshold); }
    @Override public String toString() { return "DeliverOrders" + Arrays.toString(dir) + Arrays.toString(threshold); }
}
