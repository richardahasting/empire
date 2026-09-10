package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.InfrastructureCfg;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.Terrain;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Step;

import java.util.Map;

/**
 * Step 4: efficiency rises by spending the sector's own materials and work; road levels
 * decay unless maintained. Cash is spent in canonical sector order until the treasury runs
 * dry, which is deterministic and documented.
 *
 * <p>Points are whole (issue #77, rule 3). Efficiency, road and rail are 0..100 byte scales, so half a
 * point is not a thing the world can hold; building must therefore either buy a whole point or buy
 * nothing at all. {@link #whole} floors what work, materials, cash and mobility can afford and returns
 * 0 below one — the materials stay in the sector and accrue until a whole point is affordable, which is
 * Richard's rule and also the only way the sector's books balance against what it actually got.
 */
public final class BuildUpStep implements Step {
    public String name() { return "buildup"; }

    /** What is actually built: whole points, or none — nothing is bought with a fraction (issue #77). */
    private static double whole(double points) { double p = Math.floor(points + 1e-9); return p < 1 ? 0 : p; }

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
                if (s.terrain() == Terrain.OCEAN) bridge(ctx, i, cashLeft);   // a bridge under construction or in service (issue #60)
                continue;
            }
            int cid = s.owner();

            // upkeep just for existing (KNOWN: the capital pays $1 per ETU)
            double upkeepEtu = t.maintenanceCashPerEtuOr0() * ctx.etus;
            if (upkeepEtu > 0) { cashLeft[cid] -= upkeepEtu; ctx.led.cash[cid] -= upkeepEtu; }

            // efficiency: KNOWN — half the sector's work may build, one point per work unit, $1 a point, no materials for most types
            if (!t.hasFlag("undesignated") && s.efficiency() < 100) {
                double points = Math.min(ec.maxPointsPerEtu() * ctx.etus, 100 - s.efficiency());
                double share = ec.buildWorkShare() == null ? 1.0 : ec.buildWorkShare();
                double work = ctx.workAvailablePost(i) * share;
                if (ec.workPerPoint() > 0) points = Math.min(points, work / ec.workPerPoint());
                Map<String, Double> build = t.build();
                double wanted = points;   // what work and the cap allow, before materials
                for (var e : build.entrySet()) {
                    if (e.getValue() <= 0) continue;
                    if (e.getKey().equals("cash")) points = Math.min(points, cashLeft[cid] / e.getValue());
                    else {
                        int c = ctx.com.index(e.getKey());
                        double avail = s.stock().get(c) + ctx.led.stock[i][c];
                        points = Math.min(points, avail / e.getValue());
                        if (avail < wanted * e.getValue()) ctx.led.shortOf(i, c, wanted * e.getValue() - avail);
                    }
                }
                points = whole(points);
                if (points > 0) {
                    StringBuilder used = new StringBuilder();
                    for (var e : build.entrySet()) {
                        if (e.getValue() <= 0) continue;
                        if (e.getKey().equals("cash")) { double cost = points * e.getValue(); cashLeft[cid] -= cost; ctx.led.cash[cid] -= cost; used.append(used.isEmpty() ? "" : ", ").append("$").append(Ledger.q(cost)); }
                        else { ctx.led.consume(i, ctx.com.index(e.getKey()), points * e.getValue()); used.append(used.isEmpty() ? "" : ", ").append(Ledger.q(points * e.getValue())).append(' ').append(e.getKey()); }
                    }
                    ctx.workSpent[i] += points * ec.workPerPoint();
                    ctx.led.efficiency[i] += points;
                    ctx.led.note(i, "efficiency +" + Ledger.q(points) + "%" + (used.isEmpty() ? "" : " using " + used));
                }
            }

            // road building: a standing order toward road_target, paid in materials, cash and work
            if (s.roadTarget() > s.roadLevel() + 1e-9) {
                Double mult = road.costMultiplierByTerrain().get(s.terrain().id());
                Double cap = road.maxLevelByTerrain().get(s.terrain().id());
                double ceiling = Math.min(s.roadTarget(), cap == null ? 100 : cap);
                double points = Math.min(road.maxPointsPerUpdate(), ceiling - s.roadLevel());
                double m = mult == null ? 1.0 : mult;
                double work = ctx.workAvailablePost(i);
                if (road.workPerPoint() > 0) points = Math.min(points, work / (road.workPerPoint() * m));
                double mobPerPoint = road.mobilityPerPoint() == null ? 0 : road.mobilityPerPoint() * m;
                if (mobPerPoint > 0) points = Math.min(points, Math.max(0, s.mobility() + ctx.led.mobility[i]) / mobPerPoint);
                double wantedRoad = points;
                for (var e : road.buildMaterialsPerPoint().entrySet()) {
                    double per = e.getValue() * m;
                    if (per <= 0) continue;
                    if (e.getKey().equals("cash")) points = Math.min(points, cashLeft[cid] / per);
                    else { int c = ctx.com.index(e.getKey()); double avail = s.stock().get(c) + ctx.led.stock[i][c]; points = Math.min(points, avail / per); if (avail < wantedRoad * per) ctx.led.shortOf(i, c, wantedRoad * per - avail); }
                }
                points = whole(points);
                if (points > 0) {
                    StringBuilder used = new StringBuilder();
                    for (var e : road.buildMaterialsPerPoint().entrySet()) {
                        double per = e.getValue() * m;
                        if (per <= 0) continue;
                        if (e.getKey().equals("cash")) { cashLeft[cid] -= points * per; ctx.led.cash[cid] -= points * per; used.append(used.isEmpty() ? "" : ", ").append("$").append(Ledger.q(points * per)); }
                        else { ctx.led.consume(i, ctx.com.index(e.getKey()), points * per); used.append(used.isEmpty() ? "" : ", ").append(Ledger.q(points * per)).append(' ').append(e.getKey()); }
                    }
                    ctx.workSpent[i] += points * road.workPerPoint() * m;
                    if (mobPerPoint > 0) ctx.led.mobility[i] -= points * mobPerPoint;
                    ctx.led.road[i] += points;
                    ctx.led.note(i, "road +" + Ledger.q(points) + " to " + Ledger.q(s.roadLevel() + ctx.led.road[i]) + (used.isEmpty() ? "" : " using " + used));
                }
            }

            // rail building: a standing order, needs the tech, paid in lcm + hcm + cash + mobility per point (KNOWN infra.config);
            // a mountain's first points also pay the tunnel (issue #60)
            InfrastructureCfg.RailCfg rail = ctx.cfg.infrastructure().rail();
            if (s.railTarget() > s.railLevel() + 1e-9 && ctx.country(cid).levels().tech() >= rail.techRequired())
                buildRail(ctx, i, i, cid, cashLeft, s.terrain() == Terrain.MOUNTAIN && s.railLevel() <= 1e-9 ? rail.tunnel() : null, "rail");
            if (s.railLevel() > 0) {
                double upkeep = s.railLevel() * rail.maintenanceCashPerPointPerUpdate();
                if (cashLeft[cid] >= upkeep) { cashLeft[cid] -= upkeep; ctx.led.cash[cid] -= upkeep; }
                else {
                    ctx.led.rail[i] -= Math.min(s.railLevel(), rail.decayPerUpdate());
                    ctx.led.event("rail_decay", cid, s.at(), "unpaid rail maintenance in " + s.at(), rail.decayPerUpdate());
                    ctx.led.note(i, "rail decayed " + Ledger.q(Math.min(s.railLevel(), rail.decayPerUpdate())) + ": maintenance unpaid");
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

    /**
     * Rail points on sector {@code i}, paid by sector {@code payer} of country {@code cid}. With a
     * {@code crossing} (bridge or tunnel) the first points also pay its one-time materials, all or
     * nothing. Returns the points built.
     */
    static double buildRail(Ctx ctx, int i, int payer, int cid, double[] cashLeft, InfrastructureCfg.RailCfg.Crossing crossing, String what) {
        Sector s = ctx.sector(i), p = ctx.sector(payer);
        InfrastructureCfg.RailCfg rail = ctx.cfg.infrastructure().rail();
        Double mult = rail.costMultiplierByTerrain().get(s.terrain().id());
        Double cap = rail.maxLevelByTerrain().get(s.terrain().id());
        double ceiling = Math.min(s.railTarget(), cap == null ? 100 : cap);
        double points = Math.min(rail.maxPointsPerUpdate(), ceiling - s.railLevel());
        double m = mult == null ? 1.0 : mult;
        double work = ctx.workAvailablePost(payer);
        if (rail.workPerPoint() > 0) points = Math.min(points, work / (rail.workPerPoint() * m));
        double mobPerPoint = rail.mobilityPerPoint() == null ? 0 : rail.mobilityPerPoint() * m;
        if (mobPerPoint > 0) points = Math.min(points, Math.max(0, p.mobility() + ctx.led.mobility[payer]) / mobPerPoint);
        if (crossing != null && ctx.country(cid).levels().tech() < crossing.techRequired()) return 0;
        // the crossing's one-time cost: all of it or none of it
        double crossCash = 0;
        if (crossing != null && whole(points) > 0) {
            boolean ok = true;
            for (var e : crossing.materials().entrySet()) {
                if (e.getKey().equals("cash")) { crossCash = e.getValue(); if (cashLeft[cid] < e.getValue()) ok = false; }
                else { int c = ctx.com.index(e.getKey()); double avail = p.stock().get(c) + ctx.led.stock[payer][c]; if (avail < e.getValue()) { ok = false; ctx.led.shortOf(payer, c, e.getValue() - avail); } }
            }
            if (!ok) { ctx.led.note(payer, what + " at " + s.at() + ": waiting for the " + (s.terrain() == Terrain.OCEAN ? "bridge" : "tunnel") + "'s materials"); return 0; }
        }
        double wanted = points;
        for (var e : rail.buildMaterialsPerPoint().entrySet()) {
            double per = e.getValue() * m;
            if (per <= 0) continue;
            if (e.getKey().equals("cash")) points = Math.min(points, Math.max(0, cashLeft[cid] - crossCash) / per);
            else {
                int c = ctx.com.index(e.getKey());
                double reserved = crossing == null ? 0 : crossing.materials().getOrDefault(e.getKey(), 0.0);
                double avail = p.stock().get(c) + ctx.led.stock[payer][c] - reserved;
                points = Math.min(points, Math.max(0, avail) / per);
                if (avail < wanted * per) ctx.led.shortOf(payer, c, wanted * per - avail);
            }
        }
        points = whole(points);
        if (points <= 0) return 0;
        StringBuilder used = new StringBuilder();
        if (crossing != null) {
            for (var e : crossing.materials().entrySet()) {
                if (e.getKey().equals("cash")) { cashLeft[cid] -= e.getValue(); ctx.led.cash[cid] -= e.getValue(); used.append(used.isEmpty() ? "" : ", ").append('$').append(Ledger.q(e.getValue())); }
                else { ctx.led.consume(payer, ctx.com.index(e.getKey()), e.getValue()); used.append(used.isEmpty() ? "" : ", ").append(Ledger.q(e.getValue())).append(' ').append(e.getKey()); }
            }
            used.append(" for the ").append(s.terrain() == Terrain.OCEAN ? "bridge" : "tunnel");
        }
        for (var e : rail.buildMaterialsPerPoint().entrySet()) {
            double per = e.getValue() * m;
            if (per <= 0) continue;
            if (e.getKey().equals("cash")) { cashLeft[cid] -= points * per; ctx.led.cash[cid] -= points * per; used.append(used.isEmpty() ? "" : ", ").append("$").append(Ledger.q(points * per)); }
            else { ctx.led.consume(payer, ctx.com.index(e.getKey()), points * per); used.append(used.isEmpty() ? "" : ", ").append(Ledger.q(points * per)).append(' ').append(e.getKey()); }
        }
        ctx.workSpent[payer] += points * rail.workPerPoint() * m;
        if (mobPerPoint > 0) ctx.led.mobility[payer] -= points * mobPerPoint;
        ctx.led.rail[i] += points;
        ctx.led.note(payer, what + (payer == i ? "" : " at " + s.at()) + " +" + Ledger.q(points) + " to " + Ledger.q(s.railLevel() + ctx.led.rail[i]) + (used.isEmpty() ? "" : " using " + used));
        return points;
    }

    /**
     * A bridge (issue #60): rail on a sea hex, sponsored by the adjacent land sector with the most rail
     * (ties by index) of whichever country owns it. The sponsor's stock, mobility and treasury pay;
     * unpaid maintenance decays it like any track.
     */
    static void bridge(Ctx ctx, int i, double[] cashLeft) {
        Sector s = ctx.sector(i);
        if (s.railTarget() <= 1e-9 && s.railLevel() <= 1e-9) return;
        int sponsor = -1; double best = -1;
        for (int k = 0; k < Ctx.DIRS; k++) {
            int j = ctx.neighbour(i, k);
            if (j < 0) continue;
            Sector n = ctx.sector(j);
            if (!n.owned() || !n.isLand()) continue;
            if (n.railLevel() > best) { best = n.railLevel(); sponsor = j; }
        }
        if (sponsor < 0) return;
        int cid = ctx.sector(sponsor).owner();
        InfrastructureCfg.RailCfg rail = ctx.cfg.infrastructure().rail();
        if (s.railTarget() > s.railLevel() + 1e-9 && ctx.country(cid).levels().tech() >= rail.techRequired())
            buildRail(ctx, i, sponsor, cid, cashLeft, s.railLevel() <= 1e-9 ? rail.bridge() : null, "bridge");
        if (s.railLevel() > 0) {
            double upkeep = s.railLevel() * rail.maintenanceCashPerPointPerUpdate();
            if (cashLeft[cid] >= upkeep) { cashLeft[cid] -= upkeep; ctx.led.cash[cid] -= upkeep; }
            else { ctx.led.rail[i] -= Math.min(s.railLevel(), rail.decayPerUpdate()); ctx.led.note(sponsor, "bridge at " + s.at() + " decayed " + Ledger.q(Math.min(s.railLevel(), rail.decayPerUpdate())) + ": maintenance unpaid"); }
        }
    }
}
