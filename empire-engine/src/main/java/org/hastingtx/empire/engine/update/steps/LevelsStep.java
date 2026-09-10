package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.Step;

import java.util.SplittableRandom;

/**
 * Step 9, the original's prod_nat() + age_levels(). led.level[c] holds this update's raw
 * production of {tech, research, education, happiness}; on exit it holds the delta to apply.
 */
public final class LevelsStep implements Step {
    public String name() { return "levels"; }

    public void run(Ctx ctx) {
        EconomyCfg.LevelsCfg lc = ctx.cfg.economy().levels();
        int n = ctx.led().nCountries;
        double[] civs = new double[n];
        for (int i : ctx.owned()) {          // issue #87: the owned list, not the map
            Sector s = ctx.sector(i);
            civs[s.owner()] += Math.max(0, s.stock().get(ctx.com.civ) + ctx.led().st(i, ctx.com.civ));
        }
        double[] newTech = new double[n], newRes = new double[n];
        for (Country c : ctx.snap.countries()) {
            double[] d = ctx.led().level[c.id()];
            double prodT = d[0], prodR = d[1], prodE = d[2], prodH = d[3];
            double pop = civs[c.id()] + 1;
            double E = c.levels().education(), H = c.levels().happiness();

            // education and happiness: per-ETU rates, limited, then a moving average toward the rate
            double hapEdu = 1.5 - (E + 10.0) / (E + 20.0);
            double hapRate = prodH * hapEdu * lc.happiness().consumption() / (pop * ctx.etus);
            double eduRate = prodE * lc.education().consumption() / (pop * ctx.etus);
            hapRate = EconomyCfg.LevelsCfg.limit(hapRate, lc.happiness().easy(), lc.happiness().logBase(), true);
            eduRate = EconomyCfg.LevelsCfg.limit(eduRate, lc.education().easy(), lc.education().logBase(), true);
            double newH = (H * lc.happiness().averageEtus() + hapRate * ctx.etus) / (lc.happiness().averageEtus() + ctx.etus);
            double newE = (E * lc.education().averageEtus() + eduRate * ctx.etus) / (lc.education().averageEtus() + ctx.etus);

            // tech and research: log-limited gains added to the stock, then aged 1% per level_age_rate ETUs
            double gainT = EconomyCfg.LevelsCfg.limit(prodT, lc.tech().easy(), lc.tech().logBase(), false);
            double gainR = EconomyCfg.LevelsCfg.limit(prodR, lc.research().easy(), lc.research().logBase(), false);
            double T = c.levels().tech() + gainT, R = c.levels().research() + gainR;
            if (lc.levelAgeRate() > 0) {
                T -= T * ctx.etus / (100.0 * lc.levelAgeRate());
                R -= R * ctx.etus / (100.0 * lc.levelAgeRate());
            }
            newTech[c.id()] = T; newRes[c.id()] = R;
            d[2] = newE - E; d[3] = newH - H;
        }
        // technology bleed toward the leader (KNOWN: age_levels)
        if (Boolean.TRUE.equals(lc.techBleed()) && n > 1) {
            SplittableRandom rng = Rng.stream("techbleed", ctx.seed);
            double bestT = 0, bestR = 0;
            for (int i = 0; i < n; i++) { bestT = Math.max(bestT, newTech[i]); bestR = Math.max(bestR, newRes[i]); }
            bestT /= 5; bestR /= 5;
            for (int i = 0; i < n; i++) {
                if (newTech[i] < bestT && rng.nextDouble() < 0.2) newTech[i] += (bestT - newTech[i]) / 3;
                if (newRes[i] < bestR && rng.nextDouble() < 0.2) newRes[i] += (bestR - newRes[i]) / 3;
            }
        }
        for (Country c : ctx.snap.countries()) {
            double[] d = ctx.led().level[c.id()];
            d[0] = newTech[c.id()] - c.levels().tech();
            d[1] = newRes[c.id()] - c.levels().research();
        }
    }
}
