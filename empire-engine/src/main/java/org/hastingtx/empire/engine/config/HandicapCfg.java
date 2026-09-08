package org.hastingtx.empire.engine.config;

/** Multipliers on a country's own rates. Null means "use the default". */
public record HandicapCfg(
        Double btuRate,
        Double btuCap,
        Double production,
        Double mobility,
        Double researchRate,
        Double startingCommodities,
        Integer commandBudgetPerUpdate,
        Integer sessionWindowsPerDay) {

    public static final HandicapCfg NONE = new HandicapCfg(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, null, null);

    /** Fill nulls from {@code defaults}. */
    public HandicapCfg resolve(HandicapCfg d) {
        return new HandicapCfg(
                btuRate != null ? btuRate : d.btuRate,
                btuCap != null ? btuCap : d.btuCap,
                production != null ? production : d.production,
                mobility != null ? mobility : d.mobility,
                researchRate != null ? researchRate : d.researchRate,
                startingCommodities != null ? startingCommodities : d.startingCommodities,
                commandBudgetPerUpdate != null ? commandBudgetPerUpdate : d.commandBudgetPerUpdate,
                sessionWindowsPerDay != null ? sessionWindowsPerDay : d.sessionWindowsPerDay);
    }
}
