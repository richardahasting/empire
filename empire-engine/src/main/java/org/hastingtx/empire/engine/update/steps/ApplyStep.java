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
        Ledger led = ctx.led;
        World snap = ctx.snap;
        int nCom = ctx.com.size();
        double mobMax = ctx.cfg.economy().mobility().sectorMax();

        List<Sector> next = new ArrayList<>(led.nSectors);
        for (int i = 0; i < led.nSectors; i++) {
            Sector s = ctx.sector(i);
            double[] q = s.stock().toArray();
            for (int c = 0; c < nCom; c++) {
                q[c] += led.stock[i][c];
                if (q[c] < 0) {
                    if (q[c] < -1e-6) throw new IllegalStateException("negative stock of " + ctx.com.id(c) + " at " + s.at() + ": " + q[c]);
                    q[c] = 0;
                }
                if (!ctx.com.isPerson(c)) {
                    double cap = ctx.capacity(s, c);
                    if (q[c] > cap) { led.destroyed[c] += q[c] - cap; led.event("spoilage", s.owner(), s.at(), ctx.com.id(c) + " over capacity in " + s.at(), q[c] - cap); led.note(i, Ledger.q(q[c] - cap) + " " + ctx.com.id(c) + " over capacity, lost"); q[c] = cap; }
                } else if (c == ctx.com.civ || c == ctx.com.uw) {
                    // KNOWN (human.c trunc_people): civilians and workers above the sector's population cap are truncated every update
                    double cap = ctx.maxPopulation(s);
                    if (q[c] > cap + 1e-9) { led.destroyed[c] += q[c] - cap; led.event("overcrowding", s.owner(), s.at(), ctx.com.id(c) + " over the population limit in " + s.at(), q[c] - cap); led.note(i, Ledger.q(q[c] - cap) + " " + ctx.com.id(c) + " over the population limit, lost"); q[c] = cap; }
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
        World out = new World(snap.width(), snap.height(), snap.wrapX(), snap.wrapY(), next, countries, List.of(), snap.updateNumber() + 1, List.of(), ctx.ships, snap.nextShipId(), ctx.contacts);
        checkConservation(ctx, out);
        // the last line of a sector's story: what it wanted and did not get
        for (var e : led.shortages.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < nCom; c++) if (e.getValue()[c] > 0.05) sb.append(sb.isEmpty() ? "" : ", ").append(Ledger.q(e.getValue()[c])).append(' ').append(ctx.com.id(c));
            if (!sb.isEmpty()) led.note(e.getKey(), "shortage: " + sb);
        }
        java.util.Map<String, List<String>> notes = new java.util.TreeMap<>();
        for (var e : led.notes.entrySet()) { Sector s = ctx.sector(e.getKey()); if (s.owned()) notes.put(s.at().x() + "," + s.at().y(), List.copyOf(e.getValue())); }
        return new UpdateResult(out, List.copyOf(led.events), List.copyOf(led.flows), hash(out), notes);
    }

    private static void checkConservation(Ctx ctx, World out) {
        int nCom = ctx.com.size();
        double[] before = new double[nCom], after = new double[nCom];
        for (Sector s : ctx.snap.sectors()) { for (int c = 0; c < nCom; c++) before[c] += s.stock().get(c); for (HeldParcel p : s.held()) before[p.commodity()] += p.qty(); }
        for (Sector s : out.sectors()) { for (int c = 0; c < nCom; c++) after[c] += s.stock().get(c); for (HeldParcel p : s.held()) after[p.commodity()] += p.qty(); }
        for (Ship sh : ctx.snap.ships()) for (int c = 0; c < nCom; c++) before[c] += sh.stock().get(c);
        for (Ship sh : out.ships()) for (int c = 0; c < nCom; c++) after[c] += sh.stock().get(c);
        Ledger l = ctx.led;
        for (int c = 0; c < nCom; c++) {
            double expected = before[c] + l.produced[c] + l.grown[c] - l.consumed[c] - l.destroyed[c];
            double tol = 1e-6 * Math.max(1.0, Math.abs(before[c]) + Math.abs(l.produced[c]) + Math.abs(l.consumed[c]));
            if (Math.abs(after[c] - expected) > tol)
                throw new IllegalStateException(String.format(Locale.ROOT, "conservation violated for %s: before=%.6f produced=%.6f grown=%.6f consumed=%.6f destroyed=%.6f expected=%.6f after=%.6f",
                        ctx.com.id(c), before[c], l.produced[c], l.grown[c], l.consumed[c], l.destroyed[c], expected, after[c]));
        }
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /** SHA-256 over a canonical, fixed-precision rendering of the state. */
    public static String hash(World w) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(1 << 16);
            sb.append(w.width()).append('x').append(w.height()).append('#').append(w.updateNumber()).append('\n');
            for (Sector s : w.sectors()) {
                sb.append(s.at()).append('|').append(s.terrain()).append('|').append(s.elevation()).append('|').append(s.resources()).append('|')
                  .append(s.owner()).append('|').append(s.designation()).append('|').append(f(s.efficiency())).append('|').append(f(s.mobility())).append('|')
                  .append(f(s.roadLevel())).append('|').append(f(s.roadTarget())).append('|').append(f(s.railLevel())).append('|').append(f(s.railTarget())).append('|').append(s.distCenter()).append('|').append(s.sanctuary()).append('|');
                for (int c = 0; c < s.stock().size(); c++) sb.append(f(s.stock().get(c))).append(',');
                sb.append('|');
                for (int c = 0; c < s.thresholds().length; c++) sb.append(Double.isNaN(s.thresholds()[c]) ? "-" : f(s.thresholds()[c])).append(',');
                sb.append('|');
                for (HeldParcel p : s.held()) sb.append(p.commodity()).append(':').append(f(p.qty())).append('>').append(p.dest()).append(p.rail() ? "R" : "").append(';');
                sb.append('\n');
            }
            for (Country c : w.countries()) {
                sb.append(c.id()).append('|').append(c.name()).append('|').append(c.capital()).append('|').append(f(c.cash())).append('|').append(f(c.btu())).append('|')
                  .append(f(c.levels().tech())).append(',').append(f(c.levels().research())).append(',').append(f(c.levels().education())).append(',').append(f(c.levels().happiness()))
                  .append('|').append(c.inSanctuary()).append('|').append(c.bankrupt()).append('\n');
            }
            for (Contact ct : w.contacts()) {
                sb.append("K").append(ct.owner()).append('|').append(ct.shipId()).append('|').append(ct.targetOwner()).append('|').append(ct.cls())
                  .append('|').append(ct.at()).append('|').append(ct.seenUpdate()).append('|').append(f(ct.confidence())).append('\n');
            }
            for (Ship sh : w.ships()) {
                sb.append("S").append(sh.id()).append('|').append(sh.owner()).append('|').append(sh.cls()).append('|').append(sh.at()).append('|').append(f(sh.efficiency())).append('|').append(sh.dest()).append('|');
                if (sh.lane() != null) sb.append(sh.lane().from()).append('>').append(sh.lane().to()).append(sh.lane().outbound() ? "o" : "i").append(sh.lane().cargo());
                sb.append('|');
                for (int c = 0; c < sh.stock().size(); c++) sb.append(f(sh.stock().get(c))).append(',');
                sb.append('\n');
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
