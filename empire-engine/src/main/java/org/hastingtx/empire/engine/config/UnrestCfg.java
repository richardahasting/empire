package org.hastingtx.empire.engine.config;

/**
 * Unrest (issue #72), the original's loyalty, work and guerrillas (gefla/empserver update/populace.c, human.c, revolt.c,
 * subs/takeover.c, commands/anti.c, common/hap_fact.c). Richard 2026-09-15: the original is the default. Every number is
 * KNOWN from those files. A game whose rules predate the section has no unrest: {@code economy.unrest} is null.
 */
public record UnrestCfg(Populace populace, Feed feed, Capture capture, Revolt revolt, Guerrilla guerrilla, Anti anti,
                        /** KNOWN CHE_MAX (include/sect.h). */
                        int cheMax,
                        /** KNOWN hap_fact(): a happier country fights guerrillas better, limited to this range. */
                        double hapFactMin, double hapFactMax) {

    /** update/populace.c populace(). */
    public record Populace(
            /** Each update a sector whose owner is below the happiness requirement has (requirement − happiness) / this chance of losing loyalty. */
            double requirementGapPerChance,
            /** ... by roll(roundavg(ETUs × this)), at least 1. */
            double loyaltyLossPerEtu,
            /** Above this loyalty, with fewer military than civilians / garrison_civ_per_mil, work falls. */
            int disloyalAbove,
            int garrisonCivPerMil,
            /** Work falls by loyalty − (work_loss_base + roll(work_loss_roll)). */
            int workLossBase, int workLossRoll,
            /** And the sector revolts with chance work lost × this; else unrest is reported with unrest_report_chance. */
            double revoltChancePerWorkLost, double unrestReportChance,
            /** Loyalty drifts back: with this chance it falls by roundavg(ETUs × recovery_per_etu), else rises by roundavg(ETUs × loyalty_loss_per_etu). */
            double recoveryChance, double recoveryPerEtu,
            int loyaltyMax) {}

    /** update/human.c do_feed(): starvation raises disloyalty by roll(this) + 1 and stops work; else work recovers base + roll(roll). */
    public record Feed(int starvationLoyaltyRoll, int workRecoveryBase, int workRecoveryRoll) {}

    /** subs/takeover.c takeover(). */
    public record Capture(
            /** A sector taken from another country starts at this loyalty (0 when taken back). */
            int loyalty,
            /** Guerrillas at once: n = (pivot − loyalty) + (roll(roll) − offset); if n > 0, che = civ × n / per_civ + base, at most civ / 2, ÷ hap_fact. */
            int pivot, int roll, int offset, int perCiv, int base,
            /** Until then its civilians pay tax ÷ this (KNOWN prepare.c tax(): "captured civs pay less"). */
            int occupiedTaxDivisor) {}

    /** update/revolt.c revolt(): disloyal civilians and workers take up arms. */
    public record Revolt(
            /** Civilians: n = roll_centre − roll0(roll_range); che = base + civ × n / per; at most civ / civ_share. */
            int civRollCentre, int civRollRange, int civBase, int civPer, int civShare,
            /** Workers: n = uw_roll_base + roll(uw_roll); che = uw_base + uw × n / per; at most uw. */
            int uwRollBase, int uwRoll, int uwBase, int uwPer) {}

    /** update/revolt.c guerrilla(): che with no garrison subvert; outnumbering it, fight; outnumbered less than move_ratio, sabotage; more, move. */
    public record Guerrilla(
            /** Outnumbered this many to one, che move to a neighbour of their target's with fewer military. */
            double moveRatio,
            /** Military that beat che take loyalty down by roll0(this). */
            int loyaltyRecoveryRoll,
            /** Sabotage: roll0(10) + roll0(che), at most 100, off work; the sector takes a tenth of it as damage. */
            int sabotageRoll, int sabotageMax, int sabotageDamageDivisor,
            /** Chance per update the garrison catches them: ratio × this; a fifth of the garrison (+1) engages. */
            double catchChancePerRatio, int catchShare,
            /** Loyalty rises while che recruit: below low, roll(low_roll); below high, roll(high_roll) + high_add. */
            int recruitLow, int recruitLowRoll, int recruitHigh, int recruitHighRoll, int recruitHighAdd,
            /** Recruits: civilians × roll0(recruit_roll) / recruit_per (only if occupied or loyalty above recruit_civ_loyalty); workers likewise. */
            int recruitRoll, int recruitPer, int recruitCivLoyalty,
            /** Che who beat the garrison take the sector for its old owner if loyalty is at least this; a twentieth of the people become its military. */
            int convertLoyalty, int convertMilShare,
            /** What che can tell of a neighbour's garrison: rounded to this, plus roll(jitter) − jitter_offset. */
            int spyRounding, int jitter, int jitterOffset) {}

    /** commands/anti.c: send the garrison after the che. */
    public record Anti(
            /** At most mobility / this military engage; each round costs round_mobility. */
            int mobilityPerMil, int roundMobility,
            /** A che survives a round with chance che × this / (mil + che), ÷ hap_fact. */
            double cheOddsFactor,
            /** Lost: loyalty × loyalty_kept, and 1 in lose_roll some che stay (a (roll + lose_share_add)th of them). */
            double loyaltyKept, int loseRoll, int loseShareAdd) {}

    /** KNOWN hap_fact(target, victim): happiness of the target over the other, limited; 1 when neither has any, max when only the target has. */
    public double hapFact(double targetHappiness, double otherHappiness) {
        double f;
        if (otherHappiness != 0 && targetHappiness != 0) f = targetHappiness / otherHappiness;
        else if (otherHappiness == 0 && targetHappiness == 0) f = 1.0;
        else if (targetHappiness != 0) f = hapFactMax;
        else f = hapFactMin;
        return Math.max(hapFactMin, Math.min(hapFactMax, f));
    }
}
