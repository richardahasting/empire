package org.hastingtx.empire.engine.config;

/**
 * Enlistment (issue #276). The command is Empire 1.x's: civilians answer the call in any sector of yours, straight
 * from the population, with no national reserve (1987 Usenix tapes, COMMANDS/enli.c; Richard 2026-10-03). The
 * refusals are 4.x's (gefla/empserver commands/enli.c). The centre is 4.x's update/sect.c enlist().
 */
public record EnlistCfg(
        /** KNOWN: only this share of a sector's civilians can be called up at once. */
        double civShare,
        /** KNOWN 4.x: no more military than this in a sector after enlisting. */
        int maxMilPerSector,
        /** KNOWN: BTUs per draftee; the command itself costs economy.btu.cost_by_command.enlist. */
        double btuPerDraftee,
        /** KNOWN 4.x: civilians more disloyal than this refuse to report. */
        int refuseAboveLoyalty,
        /** The enlistment centre's own conversion. Null: the centre's sector type produces as any other. */
        Centre centre) {

    /** KNOWN sect.c enlist(): etu × (baseMil + mil) × perEtu civilians become military, up to civ × civShare − mil. */
    public record Centre(String sectorType, double baseMil, double perEtu, double civShare) {}
}
