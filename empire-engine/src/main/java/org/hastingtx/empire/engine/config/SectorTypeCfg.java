package org.hastingtx.empire.engine.config;

import java.util.List;
import java.util.Map;

public record SectorTypeCfg(
        String id,
        String glyph,
        String category,
        Map<String, Double> build,
        int maxPopulation,
        Map<String, Double> produces,
        Map<String, Double> producesLevel,
        Map<String, Double> consumes,
        String resourceGate,
        LevelEffect levelEffect,
        Integer minTech,
        Map<String, Double> capacity,
        Double storeMultiplier,
        List<String> terrainRequired,
        List<String> flags,
        PlagueMitigation plagueMitigation) {

    public record LevelEffect(String level, String curve) {}

    public record PlagueMitigation(
            int radiusSectors,
            double mortalityMultiplierAt100,
            double probabilityMultiplierAt100,
            String stacking) {}

    public boolean hasFlag(String f) { return flags != null && flags.contains(f); }
    public Map<String, Double> build() { return build == null ? Map.of() : build; }
    public Map<String, Double> produces() { return produces == null ? Map.of() : produces; }
    public Map<String, Double> producesLevel() { return producesLevel == null ? Map.of() : producesLevel; }
    public Map<String, Double> consumes() { return consumes == null ? Map.of() : consumes; }
    public double storeMultiplierOr1() { return storeMultiplier == null ? 1.0 : storeMultiplier; }
    public int minTechOr0() { return minTech == null ? 0 : minTech; }
}
