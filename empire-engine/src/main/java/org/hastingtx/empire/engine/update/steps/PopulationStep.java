package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.Step;

import java.util.SplittableRandom;

/** Step 3: eat, starve, breed, plague. Closed-form compounding over the update's ETUs. */
public final class PopulationStep implements Step {
    public String name() { return "population"; }

    public void run(Ctx ctx) {
        EconomyCfg.PopulationCfg p = ctx.cfg.economy().population();
        int civ = ctx.com.civ, mil = ctx.com.mil, uw = ctx.com.uw, food = ctx.com.food;
        SplittableRandom plagueRng = Rng.stream("plague", ctx.seed);
        boolean plagueOn = ctx.cfg.options().plague();
        // one pass for the whole world, not one pass per populated sector (issue #82)
        java.util.Map<Integer, java.util.List<Sector>> mitigators = plagueOn ? mitigatorsByOwner(ctx) : java.util.Map.of();

        for (int i = 0; i < ctx.led.nSectors; i++) {
            Sector s = ctx.sector(i);
            double nCiv = s.stock().get(civ), nMil = s.stock().get(mil), nUw = s.stock().get(uw);
            if (nCiv + nMil + nUw <= 0) continue;

            // 1. eating. The first `limit` people live off the land (subsistence); only the rest draw on stock.
            EconomyCfg.PopulationCfg.SubsistenceCfg sub = p.subsistenceOrNone();
            double limit = s.isLand() ? sub.limit(s.fertility()) : 0;
            double fCiv = 0, fUw = 0, fMil = 0;   // fed by foraging, by class, in the configured order
            for (String who : sub.appliesTo()) {
                if (limit <= 0) break;
                switch (who) {
                    case "civ" -> { fCiv = Math.min(nCiv, limit); limit -= fCiv; }
                    case "uw" -> { fUw = Math.min(nUw, limit); limit -= fUw; }
                    case "mil" -> { fMil = Math.min(nMil, limit); limit -= fMil; }
                    default -> {}
                }
            }
            double subsistenceHeadroom = Math.max(0, limit);   // unused foraging capacity: births here need no stock
            double xCiv = nCiv - fCiv, xUw = nUw - fUw, xMil = nMil - fMil;   // the people who need stocked food
            double demand = (xCiv * p.foodPerCivPerEtu() + xMil * p.foodPerMilPerEtu() + xUw * p.foodPerUwPerEtu()) * ctx.etus;
            double have = s.stock().get(food);
            double foodLeft;
            if (have >= demand) {
                if (demand > 0) { ctx.led.consume(i, food, demand); if (s.owned()) ctx.led.note(i, "people ate " + Ledger.q(demand) + " food"); }
                foodLeft = have - demand;
            } else {
                if (have > 0) ctx.led.consume(i, food, have);
                if (s.owned()) { ctx.led.note(i, "people ate " + Ledger.q(have) + " food; " + Ledger.q(demand - have) + " short"); ctx.led.shortOf(i, food, demand - have); }
                foodLeft = 0;
                // KNOWN: victims = unfed people beyond what the food covers, at most half; uw starve first, then civ, then mil
                double shortfall = demand <= 0 ? 0 : 1.0 - have / demand;
                double excess = xCiv + xMil + xUw;
                double toStarve = Math.min(excess * shortfall, excess * p.starvationMaxFractionPerUpdate());
                double dCiv = 0, dMil = 0, dUw = 0;
                for (String who : p.starvationOrderOrDefault()) {
                    if (toStarve <= 0) break;
                    switch (who) {
                        case "uw" -> { dUw = Math.min(xUw, toStarve); toStarve -= dUw; }
                        case "civ" -> { dCiv = Math.min(xCiv, toStarve); toStarve -= dCiv; }
                        case "mil" -> { dMil = Math.min(xMil, toStarve); toStarve -= dMil; }
                        default -> {}
                    }
                }
                if (dCiv > 0) ctx.led.die(i, civ, dCiv);
                if (dMil > 0) ctx.led.die(i, mil, dMil);
                if (dUw > 0) ctx.led.die(i, uw, dUw);
                nCiv -= dCiv; nMil -= dMil; nUw -= dUw;
                if (s.owned() && dCiv + dMil + dUw > 0) { ctx.led.event("starvation", s.owner(), s.at(), "starvation in " + s.at(), dCiv + dMil + dUw); ctx.led.note(i, Ledger.q(dCiv + dMil + dUw) + " starved"); }
            }

            // 2. births, bounded by ceiling and by food
            if (s.owned()) {
                double ceiling = ctx.maxPopulation(s);
                double room = Math.max(0, ceiling - nCiv - nUw);
                double civBirths = nCiv * (Math.pow(1 + p.civBirthRatePerEtu(), ctx.etus) - 1);
                double uwBirths = nUw * (Math.pow(1 + p.uwBirthRatePerEtu(), ctx.etus) - 1);
                double wanted = civBirths + uwBirths;
                double allowed = Math.min(wanted, room);
                if (p.foodPerBirth() > 0) allowed = Math.min(allowed, foodLeft / (p.birthFoodReserveFactorOr2() * p.foodPerBirth()) + subsistenceHeadroom);
                if (allowed > 0 && wanted > 0) {
                    double scale = allowed / wanted;
                    double bc = civBirths * scale, bu = uwBirths * scale;
                    // births under the unused subsistence limit are free; the rest eat from stock
                    double fromStock = Math.max(0, allowed - subsistenceHeadroom);
                    if (p.foodPerBirth() > 0 && fromStock > 0) ctx.led.consume(i, food, Math.min(foodLeft, fromStock * p.foodPerBirth()));
                    if (bc > 0) ctx.led.grow(i, civ, bc);
                    if (bu > 0) ctx.led.grow(i, uw, bu);
                    nCiv += bc; nUw += bu;
                    if (bc + bu >= 0.05) ctx.led.note(i, "population +" + Ledger.q(bc + bu) + (fromStock > 0 && p.foodPerBirth() > 0 ? " (births ate " + Ledger.q(Math.min(foodLeft, fromStock * p.foodPerBirth())) + " food)" : ""));
                }
            }

            // 3. plague
            if (plagueOn && s.owned() && nCiv + nUw > 0) {
                double ceiling = Math.max(1, ctx.maxPopulation(s));
                double crowding = Math.pow((nCiv + nUw) / ceiling, p.plague().crowdingExponent());
                double[] mit = hospitalMitigation(ctx, mitigators, s);
                double prob = p.plague().baseProbabilityPerEtu() * ctx.etus * crowding * mit[1];
                if (plagueRng.nextDouble() < prob) {
                    double mort = p.plague().mortality() * mit[0];
                    ctx.led.die(i, civ, nCiv * mort);
                    if (nUw > 0) ctx.led.die(i, uw, nUw * mort);
                    ctx.led.event("plague", s.owner(), s.at(), "plague in " + s.at(), (nCiv + nUw) * mort);
                    ctx.led.note(i, "plague killed " + Ledger.q((nCiv + nUw) * mort));
                }
            }
        }
    }

    /** {mortality multiplier, probability multiplier} from the best hospital in range, else {1,1}. */
    /**
     * The plague-mitigating sectors of each country, collected in one pass (issue #82). This used to be
     * rediscovered by scanning every sector in the world for every populated sector, which is invisible
     * on a small map and quadratic on a large one.
     */
    private static java.util.Map<Integer, java.util.List<Sector>> mitigatorsByOwner(Ctx ctx) {
        java.util.Map<Integer, java.util.List<Sector>> out = new java.util.HashMap<>();
        for (int j = 0; j < ctx.led.nSectors; j++) {
            Sector h = ctx.sector(j);
            if (!h.owned()) continue;
            SectorTypeCfg t = ctx.type(h);
            if (!t.hasFlag("plague_mitigation") || t.plagueMitigation() == null) continue;
            out.computeIfAbsent(h.owner(), k -> new java.util.ArrayList<>()).add(h);
        }
        return out;
    }

    private static double[] hospitalMitigation(Ctx ctx, java.util.Map<Integer, java.util.List<Sector>> mitigators, Sector s) {
        double bestM = 1, bestP = 1;
        for (Sector h : mitigators.getOrDefault(s.owner(), java.util.List.of())) {
            SectorTypeCfg t = ctx.type(h);
            SectorTypeCfg.PlagueMitigation pm = t.plagueMitigation();
            if (org.hastingtx.empire.engine.geo.Hex.distance(ctx.snap, h.at(), s.at()) > pm.radiusSectors()) continue;
            double e = h.efficiency() / 100.0;
            double m = 1 - (1 - pm.mortalityMultiplierAt100()) * e;
            double pr = 1 - (1 - pm.probabilityMultiplierAt100()) * e;
            if (m < bestM) bestM = m;
            if (pr < bestP) bestP = pr;
        }
        return new double[] {bestM, bestP};
    }
}
