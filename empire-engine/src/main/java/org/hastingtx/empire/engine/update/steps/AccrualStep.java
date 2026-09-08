package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Step;

/** Step 2: mobility and BTUs grow with time. Nothing here interacts with anything else. */
public final class AccrualStep implements Step {
    public String name() { return "accrual"; }

    public void run(Ctx ctx) {
        EconomyCfg.MobilityCfg m = ctx.cfg.economy().mobility();
        for (int i = 0; i < ctx.led.nSectors; i++) {
            Sector s = ctx.sector(i);
            if (!s.owned()) continue;
            Country c = ctx.country(s.owner());
            double gain = m.sectorAccrualPerEtu() * ctx.etus * m.accrualFactor(s.efficiency());
            gain *= c.handicap().mobility();
            if (c.bankrupt()) gain *= ctx.cfg.economy().money().bankruptcy().effect().mobilityMultiplier();
            double room = m.sectorMax() - s.mobility();
            ctx.led.mobility[i] += Math.max(0, Math.min(gain, room));
        }
        EconomyCfg.BtuCfg b = ctx.cfg.economy().btu();
        for (Country c : ctx.snap.countries()) {
            Sector cap = ctx.snap.sector(c.capital());
            if (cap.owner() != c.id() || !ctx.type(cap).hasFlag("btu_source")) continue;
            double gain = b.accrualPerCapitalCivPerEtu() * cap.stock().get(ctx.com.civ) * ctx.etus * c.handicap().btuRate();
            double cap_ = b.max() * c.handicap().btuCap();
            ctx.led.btu[c.id()] += Math.max(0, Math.min(gain, cap_ - c.btu()));
        }
    }
}
