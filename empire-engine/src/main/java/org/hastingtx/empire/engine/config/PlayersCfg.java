package org.hastingtx.empire.engine.config;

import java.util.List;
import java.util.Map;

public record PlayersCfg(
        int maxCountries,
        double startingCash,
        StartingCommodities startingCommodities,
        double startingEfficiency,
        double startingMobility,
        Map<String, Double> startingLevels,
        SanctuaryCfg sanctuary,
        List<CountryCfg> countries) {

    public record StartingCommodities(Map<String, Double> capital, Map<String, Double> sanctuary) {}

    public record SanctuaryCfg(boolean updateRunsWhileInSanctuary, double breakCostsBtu) {}

    public record CountryCfg(String name, String controller, Map<String, Object> agent, HandicapCfg handicap) {}
}
