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

    /**
     * Stock deltas, flat: {@code stock[sector * nCom + commodity]}. Read with {@link #st}.
     *
     * <p>Signed and free to go negative or transiently past the cap — {@code int} rather than
     * {@code short} for that reason (issue #77). Flat rather than {@code int[nSectors][nCom]} because
     * the ragged form is one small array object per sector: half a million allocations and 8 MB of
     * object headers, 20 ms of an update that is now 60 (issue #87). One allocation of the same numbers
     * costs 2 ms.
     */
    public final int[] stock;

    /** This update's delta to commodity {@code c} in sector {@code i}. */
    public int st(int i, int c) { return stock[i * nCom + c]; }
    public final double[] efficiency, mobility, road, rail;
    public final double[] cash, btu;
    public final double[][] level;       // [country][tech, research, education, happiness]
    public final boolean[] bankruptNext;
    public final int[] plagueLeft;

    /**
     * Per-commodity world totals. {@code long}, and exactly integral: conservation is checked by
     * integer equality (issue #77, rule 7), so a tally that drifted by a fraction would fail the update.
     */
    public final long[] produced, consumed, destroyed, grown, transferNet;

    /**
     * Replacement held-parcel lists; null entry = unchanged from snapshot. Sparse, and it matters
     * (issue #87): the flow step used to fill every entry, which meant the apply step's
     * {@code heldNext[i] != null} test — written precisely to skip untouched sectors — never once fired.
     */
    public final List<HeldParcel>[] heldNext;

    /**
     * Parcels in the world after this update. Counted by the flow step, which knows the whole set,
     * because {@link #heldNext} is sparse and no longer sums to it.
     */
    public int heldTotal;

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
        stock = new int[nSectors * nCom];
        efficiency = new double[nSectors]; mobility = new double[nSectors]; road = new double[nSectors]; rail = new double[nSectors];
        cash = new double[nCountries]; btu = new double[nCountries];
        level = new double[nCountries][4];
        bankruptNext = new boolean[nCountries];
        plagueLeft = new int[nCountries];
        for (int i = 0; i < nCountries; i++) plagueLeft[i] = snap.countries().get(i).plagueUpdatesLeft();
        produced = new long[nCom]; consumed = new long[nCom]; destroyed = new long[nCom]; grown = new long[nCom]; transferNet = new long[nCom];
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

    public double produce(int sector, int c, double qty) { int q = (int) whole(qty); stock[sector * nCom + c] += q; produced[c] += q; return q; }
    public double consume(int sector, int c, double qty) { int q = (int) taken(qty); stock[sector * nCom + c] -= q; consumed[c] += q; return q; }
    public double destroy(int sector, int c, double qty) { int q = (int) taken(qty); stock[sector * nCom + c] -= q; destroyed[c] += q; return q; }
    public double grow(int sector, int c, double qty) { int q = (int) whole(qty); stock[sector * nCom + c] += q; grown[c] += q; return q; }
    /** Negative growth (starvation, plague) is a death: tallied as destroyed. */
    public double die(int sector, int c, double qty) { int q = (int) taken(qty); stock[sector * nCom + c] -= q; destroyed[c] += q; return q; }

    /** Move qty of c out of sector {@code from} into sector {@code to}. Sums to zero by construction. */
    public double transfer(int from, int to, int c, double qty) { int q = (int) taken(qty); stock[from * nCom + c] -= q; stock[to * nCom + c] += q; return q; }
    /** Stock leaves a sector into a held parcel (still in the world, not in any stock). */
    public double toHeld(int from, int c, double qty) { int q = (int) taken(qty); stock[from * nCom + c] -= q; transferNet[c] -= q; return q; }
    /** Held parcel arrives into a sector's stock. */
    public double fromHeld(int to, int c, double qty) { int q = (int) taken(qty); stock[to * nCom + c] += q; transferNet[c] += q; return q; }

    /**
     * Stock crosses between a sector and a ship's hold. A ship's stock is counted in the conservation
     * sum exactly like a sector's, so this is a plain move with nothing to tally — but it still has to
     * go through here, or the sector's delta stops being a whole number (issue #77).
     */
    public double toShip(int from, int c, double qty) { int q = (int) taken(qty); stock[from * nCom + c] -= q; return q; }
    public double fromShip(int to, int c, double qty) { int q = (int) taken(qty); stock[to * nCom + c] += q; return q; }

    /** A ship makes something at sea (fishing). Tallied as produced; the hold is not a sector. */
    public double produceAtSea(int c, double qty) { int q = (int) whole(qty); produced[c] += q; return q; }

    /** Something is destroyed that was never in a sector's stock — over-capacity spoilage at apply time. */
    public void destroyed(int c, double qty) { destroyed[c] += (long) qty; }

    public void event(String type, int country, org.hastingtx.empire.engine.model.Coord at, String msg, double amount) {
        events.add(new Event(type, country, at, msg, amount));
    }
}
