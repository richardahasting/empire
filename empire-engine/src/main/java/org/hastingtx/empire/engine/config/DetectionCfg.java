package org.hastingtx.empire.engine.config;

import java.util.Map;

/**
 * Detection (issue #75, first slice of #68). The original's radar was a binary range check; this is
 * probabilistic: p falls off along a sigmoid, is multiplied by the target's signature and by the
 * masking of the terrain it sits in, and a sighting becomes a contact that ages.
 */
public record DetectionCfg(
        String model,
        RadarCfg radar,
        /** Signature multiplies p; > 1 is easier to see. Keyed by the names {@link #signature} asks for. */
        Map<String, Double> targetSignature,
        Map<String, Double> maskingByTerrain,
        int contactStalenessUpdates,
        Map<String, Double> contactConfidenceBands,
        SensorCfg ship,
        SensorCfg plane,
        SatelliteCfg satellite,
        boolean adjacencyAlwaysVisible) {

    /** A radar station: a sector's radar level, its efficiency, how high it stands, and the country's tech. */
    public record RadarCfg(
            double nominalRangeAt100,
            String rangeByRadarLevel,
            double elevationBonusPer100m,
            /** Tech multiplies range: × (1 + tech_bonus_per_point × tech). GUESS. */
            double techBonusPerPoint,
            String decayCurve,
            double sigmoidK,
            double sigmoidMidpoint,
            Map<String, Double> radarLevelBuild) {}

    /** A mobile sensor — a ship's own set, a plane's. Range scales with the carrier's efficiency. */
    public record SensorCfg(double nominalRange, String decayCurve) {}

    public record SatelliteCfg(boolean enabled, double nominalRange) {}

    /** Sigmoid falloff: p(d) = 1 / (1 + exp(k · (d/range − midpoint))). Zero range never sees. */
    public double detectionProbability(double distance, double range) {
        if (range <= 0) return 0;
        double k = radar == null ? 8.0 : radar.sigmoidK();
        double mid = radar == null ? 0.85 : radar.sigmoidMidpoint();
        return 1.0 / (1.0 + Math.exp(k * (distance / range - mid)));
    }

    /** The signature multiplier for a target kind, defaulting to 1 for a kind the table does not name. */
    public double signature(String kind) {
        if (targetSignature == null) return 1.0;
        return targetSignature.getOrDefault(kind, 1.0);
    }

    /** The masking multiplier for a target sitting in this terrain; 1 where the table is silent. */
    public double masking(String terrainId) {
        if (maskingByTerrain == null) return 1.0;
        return maskingByTerrain.getOrDefault(terrainId, 1.0);
    }

    /** The band a confidence falls in: firm, probable, or faint. */
    public String band(double confidence) {
        if (contactConfidenceBands == null) return "firm";
        Double firm = contactConfidenceBands.get("firm"), probable = contactConfidenceBands.get("probable");
        if (firm != null && confidence >= firm) return "firm";
        if (probable != null && confidence >= probable) return "probable";
        return "faint";
    }
}
