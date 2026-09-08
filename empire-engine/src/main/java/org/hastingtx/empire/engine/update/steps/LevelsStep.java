package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Step;

/** Step 9: cross-curves on this update's level production, then decay and happiness consumption. */
public final class LevelsStep implements Step {
    public String name() { return "levels"; }

    public void run(Ctx ctx) {
        EconomyCfg.LevelsCfg lc = ctx.cfg.economy().levels();
        double[] civs = new double[ctx.led.nCountries];
        for (int i = 0; i < ctx.led.nSectors; i++) {
            Sector s = ctx.sector(i);
            if (s.owned()) civs[s.owner()] += Math.max(0, s.stock().get(ctx.com.civ) + ctx.led.stock[i][ctx.com.civ]);
        }
        for (Country c : ctx.snap.countries()) {
            double[] d = ctx.led.level[c.id()];
            d[1] *= ctx.curve(lc.educationToResearchMultiplier().curve(), c.levels().education());
            d[0] *= ctx.curve(lc.researchToTechMultiplier().curve(), c.levels().research());
            d[0] -= Math.min(c.levels().tech() + d[0], lc.tech().decayPerEtu() * ctx.etus);
            d[1] -= Math.min(c.levels().research() + d[1], lc.research().decayPerEtu() * ctx.etus);
            d[2] -= Math.min(c.levels().education() + d[2], lc.education().decayPerEtu() * ctx.etus);
            double hapLoss = lc.happiness().decayPerEtu() * ctx.etus;
            if (lc.happiness().consumedPerCivPerEtu() != null) hapLoss += lc.happiness().consumedPerCivPerEtu() * civs[c.id()] * ctx.etus;
            d[3] -= Math.min(c.levels().happiness() + d[3], hapLoss);
        }
    }
}
