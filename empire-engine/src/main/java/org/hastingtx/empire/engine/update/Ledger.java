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

    public void produce(int sector, int c, double qty) { stock[sector][c] += qty; produced[c] += qty; }
    public void consume(int sector, int c, double qty) { stock[sector][c] -= qty; consumed[c] += qty; }
    public void destroy(int sector, int c, double qty) { stock[sector][c] -= qty; destroyed[c] += qty; }
    public void grow(int sector, int c, double qty) { stock[sector][c] += qty; grown[c] += qty; }
    /** Negative growth (starvation, plague) is a death: tallied as destroyed. */
    public void die(int sector, int c, double qty) { stock[sector][c] -= qty; destroyed[c] += qty; }

    /** Move qty of c out of sector {@code from} into sector {@code to}. Sums to zero by construction. */
    public void transfer(int from, int to, int c, double qty) { stock[from][c] -= qty; stock[to][c] += qty; }
    /** Stock leaves a sector into a held parcel (still in the world, not in any stock). */
    public void toHeld(int from, int c, double qty) { stock[from][c] -= qty; transferNet[c] -= qty; }
    /** Held parcel arrives into a sector's stock. */
    public void fromHeld(int to, int c, double qty) { stock[to][c] += qty; transferNet[c] += qty; }

    public void event(String type, int country, org.hastingtx.empire.engine.model.Coord at, String msg, double amount) {
        events.add(new Event(type, country, at, msg, amount));
    }
}
