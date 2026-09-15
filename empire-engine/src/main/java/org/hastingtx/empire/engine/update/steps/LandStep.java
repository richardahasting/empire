package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.LandUnit;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.Stocks;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Step;

/**
 * Step 7d (issue #247, #71 slice 2): land units, as the original's prep_lands() and prod_land() ran them
 * (update/land.c; Richard 2026-09-15: the original is the default). Each unit, in id order:
 * <ol>
 * <li><b>Pay:</b> its military draw the same pay as any (money_mil).</li>
 * <li><b>Maintenance:</b> ETUs × money_land × the class's cost, doubled if its country's tech has fallen below 85% of
 *     the unit's, tripled for engineers; a treasury that cannot pay costs it ETUs / 5 efficiency instead.</li>
 * <li><b>Rations:</b> its men eat from its own food; the unfed starve, as in a sector.</li>
 * <li><b>Repair</b> (landrepair): in a sector of its owner's, toward 100%, by the sector's work over the class's bwork,
 *     at most ETUs × grow_scale, paid in the class's materials from the sector and its cost in cash; a third outside a
 *     headquarters or fortress.</li>
 * <li><b>Mobility:</b> ETUs × land_mob_scale, up to land_mob_max.</li>
 * </ol>
 */
public final class LandStep implements Step {
    public String name() { return "land"; }

    public void run(Ctx ctx) {
        UnitsCfg.LandCfg lc = ctx.cfg.units().land();
        if (lc == null || ctx.units.isEmpty()) return;
        var money = ctx.cfg.economy().money();
        var pop = ctx.cfg.economy().population();
        int mil = ctx.com.mil, food = ctx.com.food;
        for (int k = 0; k < ctx.units.size(); k++) {
            LandUnit u = ctx.units.get(k);
            UnitsCfg.LandClassCfg cls = lc.landClass(u.cls());
            if (cls == null || u.owner() < 0) continue;
            Country c = ctx.country(u.owner());
            StringBuilder note = new StringBuilder();
            int o = u.owner();

            // pay
            double men = u.stock().get(mil);
            ctx.led().cash[o] -= men * money.payPerMilPerEtu() * ctx.etus;

            // maintenance
            double mult = (c.levels().tech() < u.tech() * 0.85 ? 2 : 1) * (cls.has("engineer") ? lc.engineerMaintenanceMultiplier() : 1);
            double upkeep = mult * ctx.etus * lc.maintenancePerEtuPerCost() * cls.build().getOrDefault("cash", 0.0);
            if (c.cash() + ctx.led().cash[o] < upkeep) {
                double lost = Math.min(ctx.etus / 5.0, u.efficiency() - lc.startEfficiency());
                if (lost > 0) { u = u.withEfficiency(u.efficiency() - lost); note.append("lost ").append(Ledger.q(lost)).append("% to lack of maintenance"); }
            } else ctx.led().cash[o] -= upkeep;

            // rations: the men eat from the unit's own food, and the unfed starve
            double need = men * pop.foodPerMilPerEtu() * ctx.etus, have = u.stock().get(food);
            Stocks st = u.stock();
            if (need > 0) {
                double eat = Math.floor(Math.min(have, need));
                if (eat > 0) { st = st.plus(food, -eat); ctx.led().consumed[food] += (long) eat; }
                if (eat < need - 1e-9) {
                    double shortfall = 1 - eat / need;
                    double dead = Math.floor(Math.min(men * shortfall, men * pop.starvationMaxFractionPerUpdate()));
                    if (dead > 0) {
                        st = st.plus(mil, -dead);
                        ctx.led().destroyed[mil] += (long) dead;
                        note.append(note.isEmpty() ? "" : "; ").append(Ledger.q(dead)).append(" starved");
                        ctx.led().event("starvation", o, u.at(), "starvation in unit #" + u.id(), dead);
                    }
                }
            }
            u = u.withStock(st);

            // repair toward 100% in a sector of its owner's
            int i = ctx.snap.index(u.at());
            Sector s = ctx.sector(i);
            if (u.efficiency() < 100 && s.owner() == o) {
                double work = Math.max(0, ctx.workAvailablePost(i));
                double points = Math.floor(Math.min(Math.min(work / cls.bwork(), ctx.etus * lc.growScale()), 100 - u.efficiency()));
                for (var e : cls.build().entrySet()) {
                    if (e.getKey().equals("cash") || points <= 0) continue;
                    int ci = ctx.com.index(e.getKey());
                    double per = e.getValue() / 100.0;
                    double avail = s.stock().get(ci) + ctx.led().st(i, ci);
                    if (per > 0) points = Math.min(points, Math.floor(avail / per));
                }
                if (points > 0) {
                    double built = ctx.type(s).hasFlag("builds_units") || ctx.type(s).hasFlag("defense_bonus") ? points : Math.floor(points / lc.repairElsewhereDivisor());
                    if (built > 0) {
                        for (var e : cls.build().entrySet()) {
                            if (e.getKey().equals("cash")) continue;
                            double use = Math.ceil(built * e.getValue() / 100.0);
                            if (use > 0) ctx.led().consume(i, ctx.com.index(e.getKey()), use);
                        }
                        ctx.workSpent()[i] += built * cls.bwork();
                        ctx.led().cash[o] -= mult / (cls.has("engineer") ? lc.engineerMaintenanceMultiplier() : 1) * cls.build().getOrDefault("cash", 0.0) * built / 100.0;
                        u = u.withEfficiency(u.efficiency() + built);
                        note.append(note.isEmpty() ? "" : "; ").append("built up to ").append(Ledger.q(u.efficiency())).append('%');
                        ctx.led().note(i, "unit #" + u.id() + " built up " + Ledger.q(built) + " points");
                    }
                }
            }

            // mobility
            u = u.withMobility(Math.min(lc.mobilityMax(), u.mobility() + ctx.etus * lc.mobilityPerEtu()));
            ctx.units.set(k, u.withNote(note.toString()));
        }
    }
}
