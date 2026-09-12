package org.hastingtx.empire.engine.config;

import java.util.Map;

public record ScoringCfg(String visibility, java.util.List<String> bands,
                         Map<String, Double> weights, Map<String, Double> weightsWithUnits, boolean reportScorePerBtu) {

    /** How much a rival's standing is disclosed. Your own is always exact. */
    public String visibilityOrDefault() { return visibility == null ? "banded" : visibility; }

    public java.util.List<String> bandsOrDefault() {
        return bands == null || bands.isEmpty() ? java.util.List.of("struggling", "holding", "strong", "leading") : bands;
    }
}
