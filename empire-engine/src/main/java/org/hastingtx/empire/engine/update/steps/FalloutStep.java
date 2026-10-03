package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.model.LandUnit;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.Ship;
import org.hastingtx.empire.engine.model.Stocks;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.Step;

import java.util.Map;
import java.util.TreeMap;

/**
 * 1b. Fallout (issue #71; KNOWN update/fallout.c, run in prepare_sects before anything is produced). Where a detonation
 * left radiation, it melts what is there — the sector's stock, its land units' and its surface ships' — then leaks into
 * the neighbouring sectors and decays. Every number is {@code units.nukes.fallout}.
 *
 * <p>The original spreads sector by sector into a map it is still reading, so its result depends on the order it walks
 * the world. Here every leak is reckoned from the fallout the update began with (the update is direction-independent by
 * design); decay then works on what the leaks left.
 */
public final class FalloutStep implements Step {
    @Override public String name() { return "1b fallout"; }

    @Override public void run(Ctx ctx) {
        UnitsCfg.NukesCfg nc = ctx.cfg.units().nukes();
        if (nc == null || nc.fallout() == null || ctx.cfg.options() == null || !ctx.cfg.options().fallout()) return;
        UnitsCfg.FalloutCfg fc = nc.fallout();
        Map<Integer, Integer> hot = new TreeMap<>();
        for (int i = 0; i < ctx.nSectors; i++) if (ctx.sector(i).fallout() > 0) hot.put(i, ctx.sector(i).fallout());
        if (hot.isEmpty()) return;
        Ledger led = ctx.led();
        UnrestStep.R r = new UnrestStep.R(Rng.stream("fallout", ctx.seed));
        int etus = Math.min(ctx.etus, fc.etuCap());
        double[] melt = new double[ctx.com.size()];
        for (int c = 0; c < melt.length; c++) { Double d = fc.melt() == null ? null : fc.melt().get(ctx.com.id(c)); melt[c] = d == null ? 0 : d; }

        // melt: the sector, the land units in it, the surface ships on it (not in a sanctuary)
        for (var e : hot.entrySet()) {
            int i = e.getKey();
            double f = e.getValue();
            Sector s = ctx.sector(i);
            if (s.sanctuary()) continue;
            for (int c = 0; c < melt.length; c++) {
                double lost = melted(r, fc, melt[c], etus, f, s.stock().get(c));
                if (lost > 0) led.destroy(i, c, lost);
            }
            for (int k = 0; k < ctx.units.size(); k++) {
                LandUnit u = ctx.units.get(k);
                if (u.owner() < 0 || !u.at().equals(s.at())) continue;
                Stocks st = melt(ctx, led, r, fc, melt, etus, f, u.stock());
                if (st != u.stock()) ctx.units.set(k, u.withStock(st));
            }
            var sc = ctx.cfg.units().ships();
            for (int k = 0; k < ctx.ships.size(); k++) {
                Ship sh = ctx.ships.get(k);
                if (!sh.at().equals(s.at()) || (sc != null && sc.shipClass(sh.cls()).submarine())) continue;
                Stocks st = melt(ctx, led, r, fc, melt, etus, f, sh.stock());
                if (st != sh.stock()) ctx.ships.set(k, sh.withStock(st));
            }
        }

        // spread, from what the update began with; then decay what that leaves
        Map<Integer, Integer> next = new TreeMap<>(hot);
        for (var e : hot.entrySet()) {
            int inc = Math.max(0, r.roundavg(etus * fc.spread() * e.getValue()) - 1);
            if (inc == 0) continue;
            for (int k = 0; k < 6; k++) {
                int n = ctx.neighbour(e.getKey(), k);
                if (n < 0 || ctx.sector(n).sanctuary()) continue;
                next.merge(n, Math.min(fc.max(), ctx.sector(n).fallout() + inc), (a, b) -> Math.min(fc.max(), a + inc));
            }
        }
        for (var e : next.entrySet()) {
            int f = e.getValue();
            int decay = r.roundavg((fc.decayPerEtu() + fc.decayBase()) * fc.spread() * etus * f);
            led.fallout.put(e.getKey(), decay < f ? f - decay : 0);
        }
    }

    /** KNOWN meltitems: roundavg(qty × ETUs × fallout ÷ (melt_scale × melt)), no more than there is. */
    private static double melted(UnrestStep.R r, UnitsCfg.FalloutCfg fc, double melt, int etus, double fallout, double have) {
        if (melt <= 0 || have <= 0) return 0;
        return Math.min(Math.floor(have), r.roundavg(have * etus * fallout / (fc.meltScale() * melt)));
    }

    private static Stocks melt(Ctx ctx, Ledger led, UnrestStep.R r, UnitsCfg.FalloutCfg fc, double[] melt, int etus, double fallout, Stocks st) {
        Stocks out = st;
        for (int c = 0; c < melt.length; c++) {
            double lost = melted(r, fc, melt[c], etus, fallout, st.get(c));
            if (lost <= 0) continue;
            out = out.plus(c, -lost);
            led.destroyed(c, lost);
        }
        return out;
    }
}
