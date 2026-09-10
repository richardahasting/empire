package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.UpdateResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Step 12: apply the ledger, prove the invariants, hash the result. */
public final class ApplyStep {
    private ApplyStep() {}

    public static UpdateResult apply(Ctx ctx) {
        Ledger led = ctx.led();
        World snap = ctx.snap;
        int nCom = ctx.com.size();
        double mobMax = ctx.cfg.economy().mobility().sectorMax();

        List<Sector> next = new ArrayList<>(ctx.nSectors);
        // Sectors this step actually rebuilt, for the conservation check below.
        int[] rebuilt = new int[ctx.nSectors];
        int nRebuilt = 0;
        for (int i = 0; i < ctx.nSectors; i++) {
            Sector s = ctx.sector(i);
            // Issue #87: an unowned sector the ledger did not touch cannot have changed. Its stock, its
            // designation and therefore its caps are all exactly what the last apply left, so there is
            // nothing to add, nothing to truncate, and no reason to build a new object for it. Owned
            // sectors always take the full path even when untouched, because a designation command
            // between updates can change a cap and leave stock above it.
            if (!s.owned() && untouched(led, i, nCom)) { next.add(s); continue; }
            rebuilt[nRebuilt++] = i;
            // Whole units throughout (issue #77): the snapshot is integral, every delta is integral, so
            // the result is integral and conservation below can be checked by equality rather than tolerance.
            int[] q = new int[nCom];
            for (int c = 0; c < nCom; c++) {
                q[c] = (int) s.stock().get(c) + led.st(i, c);
                if (q[c] < 0) throw new IllegalStateException("negative stock of " + ctx.com.id(c) + " at " + s.at() + ": " + q[c]);
                if (!ctx.com.isPerson(c)) {
                    int cap = (int) Math.floor(ctx.capacity(s, c));   // a whole cap, or the stored value and the spoilage tally disagree (issue #77)
                    if (q[c] > cap) { int lost = q[c] - cap; led.destroyed(c, lost); led.event("spoilage", s.owner(), s.at(), ctx.com.id(c) + " over capacity in " + s.at(), lost); led.note(i, Ledger.q(lost) + " " + ctx.com.id(c) + " over capacity, lost"); q[c] = cap; }
                } else if (c == ctx.com.civ || c == ctx.com.uw) {
                    // KNOWN (human.c trunc_people): civilians and workers above the sector's population cap are truncated every update
                    int cap = (int) Math.floor(ctx.maxPopulation(s));
                    if (q[c] > cap) { int lost = q[c] - cap; led.destroyed(c, lost); led.event("overcrowding", s.owner(), s.at(), ctx.com.id(c) + " over the population limit in " + s.at(), lost); led.note(i, Ledger.q(lost) + " " + ctx.com.id(c) + " over the population limit, lost"); q[c] = cap; }
                }
            }
            Sector n = s.withStock(Stocks.of(q))
                    .withEfficiency(clamp(s.efficiency() + led.efficiency[i], 0, 100))
                    .withMobility(clamp(s.mobility() + led.mobility[i], 0, mobMax))
                    .withRoadLevel(clamp(s.roadLevel() + led.road[i], 0, 100))
                    .withRailLevel(clamp(s.railLevel() + led.rail[i], 0, 100));
            if (led.heldNext[i] != null) n = n.withHeld(led.heldNext[i]);
            next.add(n);
        }
        List<Country> countries = new ArrayList<>();
        for (Country c : snap.countries()) {
            double[] d = led.level[c.id()];
            Levels lv = new Levels(Math.max(0, c.levels().tech() + d[0]), Math.max(0, c.levels().research() + d[1]),
                    Math.max(0, c.levels().education() + d[2]), Math.max(0, c.levels().happiness() + d[3]));
            countries.add(new Country(c.id(), c.name(), c.capital(), c.cash() + led.cash[c.id()], c.btu() + led.btu[c.id()], lv,
                    c.handicap(), c.inSanctuary(), led.bankruptNext[c.id()], led.plagueLeft[c.id()]));
        }
        World out = new World(snap.width(), snap.height(), snap.wrapX(), snap.wrapY(), next, countries, List.of(), snap.updateNumber() + 1, List.of(), ctx.ships, snap.nextShipId(), ctx.contacts, ctx.seen, snap.railLanes());   // lanes are standing orders: they survive the update
        checkConservation(ctx, out, rebuilt, nRebuilt);
        // the last line of a sector's story: what it wanted and did not get
        for (var e : led.shortages.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < nCom; c++) if (e.getValue()[c] > 0.05) sb.append(sb.isEmpty() ? "" : ", ").append(Ledger.q(e.getValue()[c])).append(' ').append(ctx.com.id(c));
            if (!sb.isEmpty()) led.note(e.getKey(), "shortage: " + sb);
        }
        java.util.Map<String, List<String>> notes = new java.util.TreeMap<>();
        for (var e : led.notes.entrySet()) { Sector s = ctx.sector(e.getKey()); if (s.owned()) notes.put(s.at().x() + "," + s.at().y(), List.copyOf(e.getValue())); }
        return new UpdateResult(out, List.copyOf(led.events), List.copyOf(led.flows), UpdateResult.lazyHash(out), notes);
    }

    /** Nothing in the ledger moved this sector: no stock, no level, no change to its parcels. */
    private static boolean untouched(Ledger led, int i, int nCom) {
        if (led.heldNext[i] != null) return false;
        if (led.efficiency[i] != 0 || led.mobility[i] != 0 || led.road[i] != 0 || led.rail[i] != 0) return false;
        int base = i * nCom;
        for (int c = 0; c < nCom; c++) if (led.stock[base + c] != 0) return false;
        return true;
    }

    /**
     * Exact (issue #77, rule 7). Every quantity in the world is a whole number and every entry in the
     * ledger is a whole number, so the books balance to the unit or they do not balance. The old
     * {@code 1e-6} relative tolerance was there to absorb floating-point drift that can no longer occur;
     * keeping it would have hidden a real one-unit leak in a large world.
     */
    private static void checkConservation(Ctx ctx, World out, int[] rebuilt, int nRebuilt) {
        int nCom = ctx.com.size();
        long[] before = new long[nCom], after = new long[nCom];
        // Only the sectors the apply step rebuilt (issue #87). A sector it skipped is the identical
        // object in both worlds, so it adds the same amount to both sides and cancels — leaving it out
        // does not weaken the equality, it just stops summing half a million zeroes twice.
        for (int k = 0; k < nRebuilt; k++) {
            int i = rebuilt[k];
            Sector b = ctx.snap.sectors().get(i), a = out.sectors().get(i);
            for (int c = 0; c < nCom; c++) { before[c] += (long) b.stock().get(c); after[c] += (long) a.stock().get(c); }
            for (HeldParcel p : b.held()) before[p.commodity()] += (long) p.qty();
            for (HeldParcel p : a.held()) after[p.commodity()] += (long) p.qty();
        }
        for (Ship sh : ctx.snap.ships()) for (int c = 0; c < nCom; c++) before[c] += (long) sh.stock().get(c);
        for (Ship sh : out.ships()) for (int c = 0; c < nCom; c++) after[c] += (long) sh.stock().get(c);
        // Fuel in a tank is still fuel (issue #65): it left a sector but it has not left the world, and
        // it only stops being counted when it is burned, which is tallied as destroyed.
        int fuelIdx = ctx.cfg.units().ships() != null && ctx.cfg.units().ships().fuel() ? ctx.com.index(ctx.cfg.units().ships().fuelId()) : -1;
        if (fuelIdx >= 0) {
            for (Ship sh : ctx.snap.ships()) before[fuelIdx] += (long) sh.fuel();
            for (Ship sh : out.ships()) after[fuelIdx] += (long) sh.fuel();
        }
        // A crew is people who left a sector and are standing on a deck (issue #66) — still in the world.
        if (ctx.cfg.units().ships() != null && ctx.cfg.units().ships().crews()) {
            for (Ship sh : ctx.snap.ships()) before[ShipStep.crewCommodity(ctx, ctx.cfg.units().ships().shipClass(sh.cls()))] += (long) sh.crew();
            for (Ship sh : out.ships()) after[ShipStep.crewCommodity(ctx, ctx.cfg.units().ships().shipClass(sh.cls()))] += (long) sh.crew();
        }
        Ledger l = ctx.led();
        for (int c = 0; c < nCom; c++) {
            long expected = before[c] + l.produced[c] + l.grown[c] - l.consumed[c] - l.destroyed[c];
            if (after[c] == expected) continue;
            if (Boolean.getBoolean("empire.conserve.debug")) {
                for (int k = 0; k < nRebuilt; k++) {
                    int i = rebuilt[k];
                    long b = (long) ctx.snap.sectors().get(i).stock().get(c), a = (long) out.sectors().get(i).stock().get(c);
                    long bh = 0, ah = 0;
                    for (HeldParcel p : ctx.snap.sectors().get(i).held()) if (p.commodity() == c) bh += (long) p.qty();
                    for (HeldParcel p : out.sectors().get(i).held()) if (p.commodity() == c) ah += (long) p.qty();
                    long d = (a + ah) - (b + bh) - l.st(i, c);
                    if (d != 0)
                        System.err.printf("CONSERVE %s at %s: before=%d+%d after=%d+%d delta=%d mismatch=%d%n",
                                ctx.com.id(c), ctx.snap.sectors().get(i).at(), b, bh, a, ah, l.st(i, c), d);
                }
            }
            throw new IllegalStateException(String.format(Locale.ROOT, "conservation violated for %s: before=%d produced=%d grown=%d consumed=%d destroyed=%d expected=%d after=%d (out by %d)",
                    ctx.com.id(c), before[c], l.produced[c], l.grown[c], l.consumed[c], l.destroyed[c], expected, after[c], after[c] - expected));
        }
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /** SHA-256 over a canonical, fixed-precision rendering of the state. */
    public static String hash(World w) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Feed the digest as we go rather than building the whole world into one string first
            // (issue #82): at a million sectors that string was ~400 MB, and growing it was most of
            // the update. The byte stream is unchanged, so the hash is unchanged.
            StringBuilder sb = new StringBuilder(1024);
            sb.append(w.width()).append('x').append(w.height()).append('#').append(w.updateNumber()).append('\n');
            for (Sector s : w.sectors()) {
                sb.append(s.at()).append('|').append(s.terrain()).append('|').append(s.elevation()).append('|').append(s.resources()).append('|')
                  .append(s.owner()).append('|').append(s.designation()).append('|').append(f(s.efficiency())).append('|').append(f(s.mobility())).append('|')
                  .append(f(s.roadLevel())).append('|').append(f(s.roadTarget())).append('|').append(f(s.railLevel())).append('|').append(f(s.railTarget())).append('|').append(s.distCenter()).append('|').append(s.sanctuary()).append('|');
                for (int c = 0; c < s.stock().size(); c++) sb.append(f(s.stock().get(c))).append(',');
                sb.append('|');
                for (int c = 0; c < s.thresholdCount(); c++) sb.append(s.hasThreshold(c) ? f(s.threshold(c)) : "-").append(',');
                sb.append('|');
                for (HeldParcel p : s.held()) sb.append(p.commodity()).append(':').append(f(p.qty())).append('>').append(p.dest()).append(p.rail() ? "R" : "").append(';');
                sb.append('\n');
                md.update(sb.toString().getBytes(StandardCharsets.UTF_8)); sb.setLength(0);
            }
            for (Country c : w.countries()) {
                sb.append(c.id()).append('|').append(c.name()).append('|').append(c.capital()).append('|').append(f(c.cash())).append('|').append(f(c.btu())).append('|')
                  .append(f(c.levels().tech())).append(',').append(f(c.levels().research())).append(',').append(f(c.levels().education())).append(',').append(f(c.levels().happiness()))
                  .append('|').append(c.inSanctuary()).append('|').append(c.bankrupt()).append('\n');
                md.update(sb.toString().getBytes(StandardCharsets.UTF_8)); sb.setLength(0);
            }
            for (Contact ct : w.contacts()) {
                sb.append("K").append(ct.owner()).append('|').append(ct.shipId()).append('|').append(ct.targetOwner()).append('|').append(ct.cls())
                  .append('|').append(ct.at()).append('|').append(ct.seenUpdate()).append('|').append(f(ct.confidence())).append('\n');
                md.update(sb.toString().getBytes(StandardCharsets.UTF_8)); sb.setLength(0);
            }
            for (RailLane rl : w.railLanes()) {
                sb.append("L").append(rl.owner()).append('|').append(rl.from()).append('>').append(rl.to()).append('|');
                for (int c : rl.cargo()) sb.append(c).append(',');
                sb.append('\n');
                md.update(sb.toString().getBytes(StandardCharsets.UTF_8)); sb.setLength(0);
            }
            for (SeenSector ss : w.seen()) {
                sb.append("M").append(ss.owner()).append('|').append(ss.at()).append('|').append(ss.terrain())
                  .append('|').append(ss.sectorOwner()).append('|').append(ss.designation()).append('|').append(ss.seenUpdate()).append('\n');
                md.update(sb.toString().getBytes(StandardCharsets.UTF_8)); sb.setLength(0);
            }
            for (Ship sh : w.ships()) {
                sb.append("S").append(sh.id()).append('|').append(sh.owner()).append('|').append(sh.cls()).append('|').append(sh.at()).append('|').append(f(sh.efficiency())).append('|').append(sh.dest()).append('|');
                if (sh.lane() != null) sb.append(sh.lane().from()).append('>').append(sh.lane().to()).append(sh.lane().outbound() ? "o" : "i").append(sh.lane().cargo());
                sb.append('|');
                for (int c = 0; c < sh.stock().size(); c++) sb.append(f(sh.stock().get(c))).append(',');
                sb.append('\n');
                md.update(sb.toString().getBytes(StandardCharsets.UTF_8)); sb.setLength(0);
            }
            byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String f(double d) { return String.format(Locale.ROOT, "%.6f", d); }
}
