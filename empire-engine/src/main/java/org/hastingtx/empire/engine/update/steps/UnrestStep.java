package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.UnrestCfg;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.Step;

import java.util.SplittableRandom;

import static org.hastingtx.empire.engine.update.Ledger.*;

/**
 * Step 2a (issue #72): the original's guerrilla() then populace() for every owned sector, in index order, as its
 * prepare_sects() ran them (gefla/empserver update/revolt.c, update/populace.c; Richard 2026-09-15: the original is the
 * default). Disloyal sectors stop working and revolt; guerrillas fight the garrison, sabotage, recruit, and move to
 * the neighbour with the thinnest garrison; a sector they win goes back to its old owner. Work lost here counts this
 * update; starvation and recovery ({@link PopulationStep}) set the next update's.
 */
public final class UnrestStep implements Step {
    public String name() { return "unrest"; }

    public void run(Ctx ctx) {
        UnrestCfg uc = ctx.cfg.economy().unrest();
        if (uc == null) return;
        R r = new R(Rng.stream("unrest", ctx.seed));
        for (int i : ctx.owned()) {
            guerrilla(ctx, uc, r, i);
            populace(ctx, uc, r, i);
        }
    }

    /** The original's dice: roll(n) 1..n, roll0(n) 0..n-1, chance(p), roundavg(x). */
    public static final class R {
        final SplittableRandom rng;
        public R(SplittableRandom rng) { this.rng = rng; }
        public int roll(int n) { return n <= 0 ? 0 : 1 + rng.nextInt(n); }
        public int roll0(int n) { return n <= 0 ? 0 : rng.nextInt(n); }
        public boolean chance(double p) { return rng.nextDouble() < p; }
        public int roundavg(double v) { int f = (int) Math.floor(v); return f + (chance(v - f) ? 1 : 0); }
    }

    private static int now(Ctx ctx, int i, int c) { return (int) ctx.sector(i).stock().get(c) + ctx.led().st(i, c); }

    /** Who holds the sector at this point in the update: a sector che handed back already belongs to its old owner. */
    static int owner(Ctx ctx, int i) {
        int[] u = ctx.led().unrest.get(i);
        return u != null && u[U_OWNER] != OWNER_UNCHANGED ? u[U_OWNER] : ctx.sector(i).owner();
    }

    private static double happiness(Ctx ctx, int country) { return country < 0 ? 0 : ctx.country(country).levels().happiness(); }

    /** KNOWN hap_req(): (tech − 40) / 40 + education / 3. */
    static double requirement(Ctx ctx, int country) {
        var lv = ctx.cfg.economy().levels();
        double td = lv.happinessRequirementTechDivisor() == null ? 40 : lv.happinessRequirementTechDivisor();
        double ed = lv.happinessRequirementEducationDivisor() == null ? 3 : lv.happinessRequirementEducationDivisor();
        var c = ctx.country(country).levels();
        return (c.tech() - td) / td + c.education() / ed;
    }

    /** update/populace.c populace(). */
    private static void populace(Ctx ctx, UnrestCfg uc, R r, int i) {
        Sector s = ctx.sector(i);
        int civ = now(ctx, i, ctx.com.civ), mil = now(ctx, i, ctx.com.mil), uw = now(ctx, i, ctx.com.uw);
        if (civ + mil + uw <= 0) return;
        int own = owner(ctx, i);
        if (own < 0) return;
        UnrestCfg.Populace p = uc.populace();
        int[] seen = ctx.led().unrest.get(i);
        int loyal = seen == null ? s.loyalty() : seen[U_LOYAL], old = seen == null ? s.oldOwner() : seen[U_OLD];
        double hap = happiness(ctx, own), req = requirement(ctx, own);
        boolean restless = own == old && hap < req;
        if (seen == null && loyal == 0 && old == own && !restless) return;   // loyal, content, working: nothing to do or roll

        int[] u = ctx.unrest(i);
        if (own != u[U_OLD] && u[U_LOYAL] == 0) u[U_OLD] = own;
        if (own == u[U_OLD] && hap < req && r.chance((req - hap) / p.requirementGapPerChance())) {
            int n = r.roundavg(ctx.etus * p.loyaltyLossPerEtu());
            if (n == 0) n = 1;
            u[U_LOYAL] = Math.min(p.loyaltyMax(), u[U_LOYAL] + r.roll(n));
        }
        if (u[U_LOYAL] > p.disloyalAbove() && mil < civ / p.garrisonCivPerMil()) {
            int workLost = u[U_LOYAL] - (p.workLossBase() + r.roll(p.workLossRoll()));
            u[U_WORK] = Math.max(0, u[U_WORK] - workLost);
            if (r.chance(workLost * p.revoltChancePerWorkLost())) revolt(ctx, uc, r, i, u);
            else if (r.chance(p.unrestReportChance())) {
                ctx.led().event("unrest", own, s.at(), "civil unrest in " + s.at(), workLost);
                ctx.led().note(i, "civil unrest: work down to " + u[U_WORK] + "%, loyalty " + u[U_LOYAL]);
            }
        }
        if (u[U_LOYAL] > 0) {
            int n = u[U_LOYAL];
            if (r.chance(p.recoveryChance())) n -= r.roundavg(ctx.etus * p.recoveryPerEtu());
            else n += r.roundavg(ctx.etus * p.loyaltyLossPerEtu());
            u[U_LOYAL] = Math.max(0, Math.min(p.loyaltyMax(), n));
            if (u[U_LOYAL] == 0 && u[U_OLD] != own) {
                ctx.led().note(i, "the people are yours now");
                ctx.led().event("loyal", own, s.at(), s.at() + " is now fully yours", 0);
                u[U_OLD] = own;
            }
        }
        u[U_WORK_NEXT] = u[U_WORK];
    }

    /** update/revolt.c revolt(): disloyal civilians and workers take up arms against the owner. */
    private static void revolt(Ctx ctx, UnrestCfg uc, R r, int i, int[] u) {
        int own = owner(ctx, i), che = u[U_CHE];
        if (che != 0 && (u[U_TARGET] != own || che >= uc.cheMax())) return;
        int civ = now(ctx, i, ctx.com.civ), uw = now(ctx, i, ctx.com.uw);
        if (che > (civ + uw) * 3) return;
        UnrestCfg.Revolt v = uc.revolt();
        int n = v.civRollCentre() - r.roll0(v.civRollRange());
        int cheCiv = v.civBase() + civ * n / v.civPer();
        if (cheCiv < 0) cheCiv = 0;
        else if (cheCiv * v.civShare() > civ) cheCiv = civ / v.civShare();
        if (che + cheCiv > uc.cheMax()) cheCiv = uc.cheMax() - che;
        che += cheCiv;
        int cheUw = 0;
        if (che < uc.cheMax()) {
            n = v.uwRollBase() + r.roll(v.uwRoll());
            cheUw = v.uwBase() + uw * n / v.uwPer();
            if (cheUw > uw) cheUw = uw;
            if (che + cheUw > uc.cheMax()) cheUw = uc.cheMax() - che;   // the original subtracts che_uw here, a slip; this keeps che within CHE_MAX
            che += cheUw;
        }
        if (cheCiv + cheUw > 0) {
            if (cheCiv > 0) ctx.led().die(i, ctx.com.civ, cheCiv);
            if (cheUw > 0) ctx.led().die(i, ctx.com.uw, cheUw);
            u[U_CHE] = che;
            u[U_TARGET] = own;
            Sector s = ctx.sector(i);
            ctx.led().event("revolt", own, s.at(), "revolt in " + s.at(), cheCiv + cheUw);
            ctx.led().note(i, "revolt: " + (cheCiv + cheUw) + " took up arms (" + che + " guerrillas)");
        }
    }

    /** update/revolt.c guerrilla(). Land units, and so security troops, come with #71. */
    private static void guerrilla(Ctx ctx, UnrestCfg uc, R r, int i) {
        int[] seen = ctx.led().unrest.get(i);
        int che = seen == null ? ctx.sector(i).che() : seen[U_CHE];
        if (che <= 0) return;
        int[] u = ctx.unrest(i);
        UnrestCfg.Guerrilla g = uc.guerrilla();
        Sector s = ctx.sector(i);
        int civ = now(ctx, i, ctx.com.civ), uw = now(ctx, i, ctx.com.uw), mil = now(ctx, i, ctx.com.mil);
        int victim = owner(ctx, i), actor = u[U_OLD], target = u[U_TARGET];
        if (target < 0) { u[U_CHE] = 0; return; }
        // land units of the owner's there count with the garrison; security troops raid first (KNOWN guerrilla(), issue #247)
        double security = 0;
        int sectorMil = mil;
        var land = ctx.cfg.units().land();
        if (land != null) for (var lu : ctx.units) {
            if (lu.owner() != victim || !lu.at().equals(s.at())) continue;
            int um = (int) lu.stock().get(ctx.com.mil);
            mil += um;
            var cls = land.landClass(lu.cls());
            if (cls == null || target != victim || !cls.has("security")) continue;
            security += land.securityBonus() * um * lu.efficiency() / 100.0;
            int reach = (int) (um * lu.efficiency() / land.securityKillDivisor());
            int kill = Math.min(che, reach < 1 ? 0 : r.roll(reach));
            if (kill > 0) { che -= kill; ctx.led().note(i, "unit #" + lu.id() + " killed " + kill + " guerrillas in a raid"); }
        }
        if (che <= 0) { u[U_CHE] = 0; u[U_TARGET] = -1; return; }
        boolean recruit = false, convert = false, move = false;
        int mc = 0, cc = 0;
        double hf = uc.hapFact(happiness(ctx, target), happiness(ctx, actor));
        if (victim != target) move = true;
        else {
            double ratio = (mil + security) / che;
            double odds = (double) che / (mil + security + che) / hf;
            if (mil == 0) {
                ctx.led().note(i, "revolutionary subversion reported");
                recruit = true; convert = true;
            } else if (che > mil) {
                // shoot it out with the military
                while (che > cc && mil > mc) { if (r.chance(odds)) mc++; else cc++; }
                if (mil > mc) u[U_LOYAL] = Math.max(0, u[U_LOYAL] - r.roll0(g.loyaltyRecoveryRoll()));
                else { convert = true; recruit = true; }
                casualties(ctx, i, s, victim, sectorMil, mc);
                che -= cc; mil -= mc;
            } else if (ratio < g.moveRatio()) {
                // guerrillas resort to blowing things up, which disrupts work
                int n = Math.min(g.sabotageMax(), r.roll0(g.sabotageRoll()) + r.roll0(che));
                u[U_WORK] = Math.max(0, u[U_WORK] - n);
                u[U_WORK_NEXT] = u[U_WORK];
                ctx.led().note(i, "production disrupted by terrorists (" + n + "% of the work)");
                damage(ctx, r, i, n / g.sabotageDamageDivisor());
                recruit = true;
            } else move = true;
            if (mil > 0 && che > 0 && r.chance(ratio * g.catchChancePerRatio())) {
                // the garrison catches them: a fifth of it engages
                int n = mil / g.catchShare() + 1;
                double o = (double) che / (n + security / g.catchShare() + che) / hf;
                while (che > cc && n > mc) { if (r.chance(o)) mc++; else cc++; }
                int killed = Math.min(mc, mil);
                casualties(ctx, i, s, victim, sectorMil, killed);
                che -= cc; mil -= killed;
                recruit = false;
            }
        }
        if (convert && u[U_LOYAL] >= g.convertLoyalty()) {
            // the che won and the sector goes back to whose people they are; nobody's, if they were the owner's own
            int to = victim == actor ? -1 : actor;
            u[U_OWNER] = to;
            u[U_LOYAL] = 0;
            u[U_OLD] = to;
            int people = Math.max(0, civ) + Math.max(0, uw);
            if (uw > 0) { ctx.led().die(i, ctx.com.uw, uw); ctx.led().grow(i, ctx.com.civ, uw); }
            int militia = people / g.convertMilShare();
            if (militia > 0) { ctx.led().die(i, ctx.com.civ, militia); ctx.led().grow(i, ctx.com.mil, militia); }
            civ = people - militia; uw = 0; mil = militia;
            move = true; recruit = false;
            ctx.led().event("partisans", victim, s.at(), "partisans take over " + s.at(), people);
            ctx.led().note(i, "partisans took the sector" + (to < 0 ? "" : " back for " + ctx.country(to).name()));
        }
        if (recruit && che > 0) {
            int n = u[U_LOYAL];
            if (n < g.recruitLow()) n += r.roll(g.recruitLowRoll());
            else if (n < g.recruitHigh()) n += r.roll(g.recruitHighRoll()) + g.recruitHighAdd();
            u[U_LOYAL] = Math.min(uc.populace().loyaltyMax(), n);
            if (u[U_OLD] != owner(ctx, i) || n > g.recruitCivLoyalty()) {
                int take = (int) (civ * r.roll0(g.recruitRoll()) / g.recruitPer() / hf);
                if (take + che > uc.cheMax()) take = uc.cheMax() - che;
                take = Math.max(0, Math.min(take, civ));
                if (take > 0) { ctx.led().die(i, ctx.com.civ, take); civ -= take; che += take; }
            }
            int take = uw * r.roll0(g.recruitRoll()) / g.recruitPer();
            if (take + che > uc.cheMax()) take = uc.cheMax() - che;
            take = Math.max(0, Math.min(take, uw));
            if (take > 0) { ctx.led().die(i, ctx.com.uw, take); uw -= take; che += take; }
        }
        if (move && che > 0) {
            int best = -1, fewest = convert ? 999 : mil;
            for (int d = 0; d < 6; d++) {
                int j = ctx.neighbour(i, d);
                if (j < 0) continue;
                Sector nb = ctx.sector(j);
                if (!nb.isLand() || owner(ctx, j) != target) continue;
                int[] nu = ctx.led().unrest.get(j);
                int nche = nu == null ? nb.che() : nu[U_CHE], ntarget = nu == null ? nb.cheTarget() : nu[U_TARGET];
                if (nche > 0 && (ntarget != target || nche + che > uc.cheMax())) continue;
                // what che can tell of a garrison: rounded, with a little noise
                int val = (int) (Math.round(now(ctx, j, ctx.com.mil) / (double) g.spyRounding()) * g.spyRounding()) + r.roll(g.jitter()) - g.jitterOffset();
                if (val >= fewest) continue;
                best = j; fewest = val;
            }
            if (best >= 0) {
                int[] nu = ctx.unrest(best);
                nu[U_CHE] += che;
                nu[U_TARGET] = target;
                ctx.led().note(i, che + " guerrillas moved on to " + ctx.sector(best).at());
                che = 0;
            }
        }
        u[U_CHE] = Math.max(0, che);
        u[U_TARGET] = che > 0 ? target : -1;
        if (mc > 0 || cc > 0) {
            ctx.led().event("guerrilla", target, s.at(), "guerrilla warfare in " + s.at(), mc + cc);
            ctx.led().note(i, "guerrilla warfare: " + mc + " troops and " + cc + " rebels killed");
        }
    }

    /** KNOWN take_casualties(): the sector's military fall first, then the owner's units there. */
    private static void casualties(Ctx ctx, int i, Sector s, int owner, int sectorMil, int dead) {
        int fromSector = Math.min(dead, sectorMil);
        if (fromSector > 0) ctx.led().die(i, ctx.com.mil, fromSector);
        int left = dead - fromSector;
        for (int k = 0; k < ctx.units.size() && left > 0; k++) {
            var lu = ctx.units.get(k);
            if (lu.owner() != owner || !lu.at().equals(s.at())) continue;
            int take = Math.min(left, (int) lu.stock().get(ctx.com.mil));
            if (take <= 0) continue;
            ctx.units.set(k, lu.withStock(lu.stock().plus(ctx.com.mil, -take)));
            ctx.led().destroyed[ctx.com.mil] += take;
            left -= take;
        }
    }

    /** subs/sectdamage.c sect_damage(): efficiency, road, rail, mobility and every item lose {@code dam} percent. */
    static void damage(Ctx ctx, R r, int i, int dam) {
        if (dam <= 0) return;
        dam = Math.min(100, dam);
        Sector s = ctx.sector(i);
        Ledger led = ctx.led();
        led.efficiency[i] -= r.roundavg((s.efficiency() + led.efficiency[i]) * dam / 100.0);
        led.road[i] -= r.roundavg((s.roadLevel() + led.road[i]) * dam / 100.0);
        led.rail[i] -= r.roundavg((s.railLevel() + led.rail[i]) * dam / 100.0);
        double mob = s.mobility() + led.mobility[i];
        if (mob > 0) led.mobility[i] -= r.roundavg(mob * dam / 100.0);
        for (int c = 0; c < ctx.com.size(); c++) {
            int have = now(ctx, i, c);
            int lost = r.roundavg(have * dam / 100.0);
            if (lost > 0) led.destroy(i, c, Math.min(lost, have));
        }
    }
}
