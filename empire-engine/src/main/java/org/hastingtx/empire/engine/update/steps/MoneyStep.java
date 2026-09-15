package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Step;

/** Step 8: taxes in, pay out, interest, bankruptcy flag for the NEXT update. */
public final class MoneyStep implements Step {
    public String name() { return "money"; }

    public void run(Ctx ctx) {
        EconomyCfg.MoneyCfg m = ctx.cfg.economy().money();
        double[] income = new double[ctx.led().nCountries], expense = new double[ctx.led().nCountries];
        boolean interest = ctx.cfg.options().interest() && ctx.com.has("bar");
        int bar = interest ? ctx.com.index("bar") : -1;
        for (int i : ctx.owned()) {          // issue #87: the owned list, not the map
            Sector s = ctx.sector(i);
            int c = s.owner();
            double civ = Math.max(0, s.stock().get(ctx.com.civ) + ctx.led().st(i, ctx.com.civ));
            double uw = Math.max(0, s.stock().get(ctx.com.uw) + ctx.led().st(i, ctx.com.uw));
            double mil = Math.max(0, s.stock().get(ctx.com.mil) + ctx.led().st(i, ctx.com.mil));
            // KNOWN (update/prepare.c tax): civilians and workers pay by the sector's efficiency; military pay is not scaled (issue #221)
            income[c] += (civ * m.taxPerCivPerEtu() + uw * m.taxPerUwPerEtu()) * ctx.etus * s.efficiency() / 100;
            expense[c] += mil * m.payPerMilPerEtu() * ctx.etus;
            // KNOWN (update/prepare.c bank_income): bars × ETUs × bankint × efficiency / 100 (issue #219)
            if (interest && ctx.type(s).hasFlag("interest_bearing")) income[c] += Math.max(0, s.stock().get(bar) + ctx.led().st(i, bar)) * m.bankInterestPerBarPerEtu() * ctx.etus * s.efficiency() / 100;
        }
        for (Country c : ctx.snap.countries()) {
            ctx.led().cash[c.id()] += income[c.id()] - expense[c.id()];
            double post = c.cash() + ctx.led().cash[c.id()];
            boolean bankrupt = post < m.bankruptcy().threshold();
            ctx.led().bankruptNext[c.id()] = bankrupt;
            if (bankrupt && !c.bankrupt()) ctx.led().event("bankrupt", c.id(), c.capital(), c.name() + " is bankrupt", post);
        }
    }
}
