package org.hastingtx.empire.engine.config;

import java.util.Map;

public record InfrastructureCfg(RoadCfg road, RailCfg rail) {

    public record RoadCfg(
            Map<String, Double> buildMaterialsPerPoint,
            double workPerPoint,
            /** Sector mobility spent per point (KNOWN: infra.config bmob 100 per 100 points = 1). */
            Double mobilityPerPoint,
            double maxPointsPerUpdate,
            Map<String, Double> costMultiplierByTerrain,
            Map<String, Double> maxLevelByTerrain,
            CurveCfg mobilityDiscountCurve,
            double decayPerUpdate,
            double maintenanceCashPerPointPerUpdate,
            double combatDamageFraction) {}

    public record RailCfg(
            int techRequired,
            Map<String, Double> buildMaterialsPerPoint,
            double workPerPoint,
            double maxPointsPerUpdate,
            boolean requiresDepotEndpoints,
            double minLevelToCarry,
            double capacityPerUpdateAt100,
            String costPerShipment,
            double cashPer100UnitsShipped,
            EconomyCfg.TechScaled maxSectorsPerUpdate,
            double decayPerUpdate,
            double maintenanceCashPerPointPerUpdate,
            String severedRouteBehavior,
            double combatDamageFraction,
            Map<String, Double> costMultiplierByTerrain,
            Map<String, Double> maxLevelByTerrain,
            Crossing bridge,
            Crossing tunnel) {
        public record Crossing(int techRequired, Map<String, Double> materials) {}
    }
}
