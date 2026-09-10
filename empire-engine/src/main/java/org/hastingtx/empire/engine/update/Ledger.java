package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.model.HeldParcel;
import org.hastingtx.empire.engine.model.World;

import java.util.ArrayList;
import java.util.List;

/**
 * All deltas an update intends to make, accumulated against the frozen snapshot and applied
 * once. Every stock change is attributed to a source/sink class so conservation can be
 * proven at apply time: transfers must sum to zero; everything else is tallied.
 */
public final class Ledger {
    public final int nSectors, nCom, nCountries;

    public final double[][] stock;       // [sector][commodity]
    public final double[] efficiency, mobility, road, rail;
    public final double[] cash, btu;
    public final double[][] level;       // [country][tech, research, education, happiness]
    public final boolean[] bankruptNext;
    public final int[] plagueLeft;

    // per-commodity tallies (world totals)
    public final double[] produced, consumed, destroyed, grown, transferNet;

    /** Replacement held-parcel lists; null entry = unchanged from snapshot. */
    public final List<HeldParcel>[] heldNext;

    public final List<Event> events = new ArrayList<>();
    public final List<Flow> flows = new ArrayList<>();
    /** Plain-language lines per sector, in the order the steps wrote them (issue #49): "made 430 lcm using 430 iron". */
    public final java.util.Map<Integer, List<String>> notes = new java.util.HashMap<>();
    public void note(int sector, String line) { notes.computeIfAbsent(sector, k -> new ArrayList<>()).add(line); }
    /** What a sector wanted and did not get this update, by commodity — summarised as its last note (Richard 2026-09-09). */
    public final java.util.Map<Integer, double[]> shortages = new java.util.HashMap<>();
    public void shortOf(int sector, int c, double qty) { if (qty > 1e-9) shortages.computeIfAbsent(sector, k -> new double[nCom])[c] += qty; }
    /** Quantities in notes: whole numbers, one decimal below 10, nothing below 0.05. */
    public static String q(double v) { double a = Math.abs(v); return a >= 10 ? String.valueOf(Math.round(v)) : a < 0.05 ? "0" : String.format(java.util.Locale.ROOT, "%.1f", v); }

    @SuppressWarnings("unchecked")
    public Ledger(World snap, int nCom) {
        this.nSectors = snap.sectors().size();
        this.nCom = nCom;
        this.nCountries = snap.countries().size();
        stock = new double[nSectors][nCom];
        efficiency = new double[nSectors]; mobility = new double[nSectors]; road = new double[nSectors]; rail = new double[nSectors];
        cash = new double[nCountries]; btu = new double[nCountries];
        level = new double[nCountries][4];
        bankruptNext = new boolean[nCountries];
        plagueLeft = new int[nCountries];
        for (int i = 0; i < nCountries; i++) plagueLeft[i] = snap.countries().get(i).plagueUpdatesLeft();
        produced = new double[nCom]; consumed = new double[nCom]; destroyed = new double[nCom]; grown = new double[nCom]; transferNet = new double[nCom];
        heldNext = (List<HeldParcel>[]) new List[nSectors];
    }

    /**
     * Quantities are whole units (issue #77). Every stock movement is rounded here, at the moment it is
     * recorded, so the tally and the stock always agree and conservation holds exactly rather than
     * within a tolerance. Half-up, per Richard 2026-09-09: 2.5 becomes 3. Each method returns what it
     * actually moved, so a caller that needs the quantity elsewhere — a held parcel, a ship's hold —
     * uses the same whole number and nothing is invented or lost between the two.
     *
     * <p>Richard's rule, same day: if it cannot make one whole unit it does not happen and takes
     * nothing, which falls out of rounding a sub-half quantity to zero.
     */
    public static double whole(double qty) { return Math.round(qty); }

    /**
     * What a sector actually gives up. Taking floors rather than rounding half-up: stock is whole, so
     * rounding 1.5 up out of a stock of 1 would overdraw it into the negative. Flooring is also
     * Richard's rule for the other direction — if it cannot make a whole unit it does not happen and
     * takes nothing (2026-09-09).
     */
    public static double taken(double qty) { return Math.floor(qty); }

    public double produce(int sector, int c, double qty) { double q = whole(qty); stock[sector][c] += q; produced[c] += q; return q; }
    public double consume(int sector, int c, double qty) { double q = taken(qty); stock[sector][c] -= q; consumed[c] += q; return q; }
    public double destroy(int sector, int c, double qty) { double q = taken(qty); stock[sector][c] -= q; destroyed[c] += q; return q; }
    public double grow(int sector, int c, double qty) { double q = whole(qty); stock[sector][c] += q; grown[c] += q; return q; }
    /** Negative growth (starvation, plague) is a death: tallied as destroyed. */
    public double die(int sector, int c, double qty) { double q = taken(qty); stock[sector][c] -= q; destroyed[c] += q; return q; }

    /** Move qty of c out of sector {@code from} into sector {@code to}. Sums to zero by construction. */
    public double transfer(int from, int to, int c, double qty) { double q = taken(qty); stock[from][c] -= q; stock[to][c] += q; return q; }
    /** Stock leaves a sector into a held parcel (still in the world, not in any stock). */
    public double toHeld(int from, int c, double qty) { double q = taken(qty); stock[from][c] -= q; transferNet[c] -= q; return q; }
    /** Held parcel arrives into a sector's stock. */
    public double fromHeld(int to, int c, double qty) { double q = taken(qty); stock[to][c] += q; transferNet[c] += q; return q; }

    public void event(String type, int country, org.hastingtx.empire.engine.model.Coord at, String msg, double amount) {
        events.add(new Event(type, country, at, msg, amount));
    }
}
