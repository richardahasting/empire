package org.hastingtx.empire.engine.config;

import java.util.Map;

/** Bound for validation; the M0 engine does not evaluate detection. */
public record DetectionCfg(
        String model,
        Map<String, Object> radar,
        Map<String, Double> targetSignature,
        Map<String, Double> maskingByTerrain,
        int contactStalenessUpdates,
        Map<String, Double> contactConfidenceBands,
        Map<String, Object> ship,
        Map<String, Object> plane,
        Map<String, Object> satellite,
        boolean adjacencyAlwaysVisible) {}
