package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.InfrastructureCfg;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Step;

import java.util.Map;

/**
 * Step 4: efficiency rises by spending the sector's own materials and work; road levels
 * decay unless maintained. Cash is spent in canonical sector order until the treasury runs
 * dry, which is deterministic and documented.
 */
public final class BuildUpStep implements Step {
    public String name() { return "buildup"; }

    public void run(Ctx ctx) {
        EconomyCfg.EfficiencyCfg ec = ctx.cfg.economy().efficiency();
        InfrastructureCfg.RoadCfg road = ctx.cfg.infrastructure().road();
        double[] cashLeft = new double[ctx.led.nCountries];
        for (Country c : ctx.snap.countries()) cashLeft[c.id()] = Math.max(0, c.cash() + ctx.led.cash[c.id()]);

        for (int i = 0; i < ctx.led.nSectors; i++) {
            Sector s = ctx.sector(i);
            SectorTypeCfg t = ctx.type(s);
            if (!s.owned()) {
                if (s.efficiency() > 0 && !t.hasFlag("undesignated")) ctx.led.efficiency[i] -= Math.min(s.efficiency(), ec.decayPerUpdateIfUnowned());
                continue;
            }
            int cid = s.owner();

            // efficiency
            if (!t.hasFlag("undesignated") && s.efficiency() < 100) {
                double points = Math.min(ec.maxPointsPerEtu() * ctx.etus, 100 - s.efficiency());
                double work = ctx.workAvailablePost(i);
                if (ec.workPerPoint() > 0) points = Math.min(points, work / ec.workPerPoint());
                Map<String, Double> build = t.build();
                for (var e : build.entrySet()) {
                    if (e.getValue() <= 0) continue;
                    if (e.getKey().equals("cash")) points = Math.min(points, cashLeft[cid] / e.getValue());
                    else {
                        int c = ctx.com.index(e.getKey());
                        double avail = s.stock().get(c) + ctx.led.stock[i][c];
                        points = Math.min(points, avail / e.getValue());
                    }
                }
                if (points > 1e-9) {
                    for (var e : build.entrySet()) {
                        if (e.getValue() <= 0) continue;
                        if (e.getKey().equals("cash")) { double cost = points * e.getValue(); cashLeft[cid] -= cost; ctx.led.cash[cid] -= cost; }
                        else ctx.led.consume(i, ctx.com.index(e.getKey()), points * e.getValue());
                    }
                    ctx.workSpent[i] += points * ec.workPerPoint();
                    ctx.led.efficiency[i] += points;
                }
            }

            // road maintenance / decay
            if (s.roadLevel() > 0) {
                double upkeep = s.roadLevel() * road.maintenanceCashPerPointPerUpdate();
                if (cashLeft[cid] >= upkeep) { cashLeft[cid] -= upkeep; ctx.led.cash[cid] -= upkeep; }
                else {
                    ctx.led.road[i] -= Math.min(s.roadLevel(), road.decayPerUpdate());
                    ctx.led.event("road_decay", cid, s.at(), "unpaid road maintenance in " + s.at(), road.decayPerUpdate());
                }
            }
        }
    }
}
