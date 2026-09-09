package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Step;

import java.util.Map;

/**
 * Step 5: produced = min(work-limited output, input-limited output, capacity). Inputs are
 * consumed in proportion to what was actually produced, so conservation is exact.
 */
public final class ProductionStep implements Step {
    private static final String[] LEVEL_NAMES = {"tech", "research", "education", "happiness"};
    public String name() { return "production"; }

    private static final String[] LEVELS = {"tech", "research", "education", "happiness"};

    public void run(Ctx ctx) {
        for (int i = 0; i < ctx.led.nSectors; i++) {
            Sector s = ctx.sector(i);
            if (!s.owned()) continue;
            SectorTypeCfg t = ctx.type(s);
            if (t.produces().isEmpty() && t.producesLevel().isEmpty()) continue;
            Country c = ctx.country(s.owner());
            Double minEff = ctx.cfg.economy().efficiency().productionMinEfficiency();
            if (minEff != null && s.efficiency() < minEff) continue;          // KNOWN: nothing below 60%
            double work = ctx.workAvailablePost(i);
            if (work <= 0) continue;

            double unit = work * (s.efficiency() / 100.0) * c.handicap().production();   // work already carries the ETUs
            if (t.resourceGate() != null) unit *= s.resources().get(t.resourceGate()) / 100.0;
            if (t.levelEffect() != null) unit *= ctx.curve(t.levelEffect().curve(), c.levels().get(t.levelEffect().level()));
            if (c.bankrupt()) unit *= ctx.cfg.economy().money().bankruptcy().effect().productionMultiplier();
            if (unit <= 0) continue;

            // wanted outputs
            Map<String, Double> produces = t.produces(), producesLevel = t.producesLevel(), consumes = t.consumes();
            double[] want = new double[ctx.com.size()];
            double[] wantLevel = new double[4];
            double totalWant = 0;
            for (var e : produces.entrySet()) {
                int ci = ctx.com.index(e.getKey());
                double w = unit * e.getValue();
                double room = ctx.com.isPerson(ci) ? Math.max(0, ctx.maxPopulation(s) - people(ctx, i))
                                                  : Math.max(0, ctx.capacity(s, ci) - (s.stock().get(ci) + ctx.led.stock[i][ci]));
                want[ci] = Math.min(w, room);
                totalWant += want[ci];
            }
            for (var e : producesLevel.entrySet()) {
                int li = levelIndex(e.getKey());
                double w = unit * e.getValue();
                if (li == 0 || li == 1) w *= c.handicap().researchRate();
                wantLevel[li] = w;
                totalWant += w;
            }
            if (totalWant <= 0) continue;

            // input limit: consumes[in] is units of input per unit of output (summed over outputs)
            double scale = 1.0;
            for (var e : consumes.entrySet()) {
                int in = ctx.com.index(e.getKey());
                double need = totalWant * e.getValue();
                if (need <= 0) continue;
                double avail = Math.max(0, s.stock().get(in) + ctx.led.stock[i][in]);
                scale = Math.min(scale, avail / need);
            }
            if (scale <= 0) continue;

            double producedTotal = 0;
            StringBuilder made = new StringBuilder(), used = new StringBuilder();
            for (int ci = 0; ci < want.length; ci++) if (want[ci] > 0) { ctx.led.produce(i, ci, want[ci] * scale); producedTotal += want[ci] * scale; made.append(made.isEmpty() ? "" : ", ").append(Ledger.q(want[ci] * scale)).append(' ').append(ctx.com.id(ci)); }
            for (int li = 0; li < 4; li++) if (wantLevel[li] > 0) { ctx.led.level[c.id()][li] += wantLevel[li] * scale; producedTotal += wantLevel[li] * scale; made.append(made.isEmpty() ? "" : ", ").append(Ledger.q(wantLevel[li] * scale)).append(' ').append(LEVEL_NAMES[li]); }
            for (var e : consumes.entrySet()) {
                int in = ctx.com.index(e.getKey());
                double q = producedTotal * e.getValue();
                if (q > 0) { ctx.led.consume(i, in, q); used.append(used.isEmpty() ? "" : ", ").append(Ledger.q(q)).append(' ').append(e.getKey()); }
            }
            double cashPer = t.productionCashPerUnitOr0();
            if (cashPer > 0) { ctx.led.cash[c.id()] -= cashPer * producedTotal; used.append(used.isEmpty() ? "" : ", ").append('$').append(Ledger.q(cashPer * producedTotal)); }   // KNOWN: guns $30, shells $3, tech $300...
            if (!made.isEmpty()) ctx.led.note(i, "made " + made + (used.isEmpty() ? "" : " using " + used) + (scale < 1 - 1e-9 ? " (short of inputs)" : ""));
        }
    }

    private static double people(Ctx ctx, int i) {
        Sector s = ctx.sector(i);
        return s.stock().get(ctx.com.civ) + ctx.led.stock[i][ctx.com.civ] + s.stock().get(ctx.com.uw) + ctx.led.stock[i][ctx.com.uw];
    }

    static int levelIndex(String id) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(id)) return i;
        throw new IllegalArgumentException("unknown level: " + id);
    }
}
