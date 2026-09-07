package org.hastingtx.empire.engine.config;

import java.util.Map;

public record ScoringCfg(Map<String, Double> weights, Map<String, Double> weightsWithUnits, boolean reportScorePerBtu) {}
