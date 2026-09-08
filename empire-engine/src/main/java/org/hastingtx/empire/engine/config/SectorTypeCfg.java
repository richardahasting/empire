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
        PlagueMitigation plagueMitigation,
        /** Packing class for shipping weight: normal | warehouse | bank | urban | inefficient (KNOWN: sect.config pkg). */
        String packing,
        /** Cash charged per unit produced (KNOWN: product.config cost). */
        Double productionCashPerUnit,
        /** Cash charged per ETU just for existing (KNOWN: sect.config maint; the capital pays 1). */
        Double maintenanceCashPerEtu) {

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
    public String packingOrNormal() { return packing == null ? "normal" : packing; }
    public double productionCashPerUnitOr0() { return productionCashPerUnit == null ? 0 : productionCashPerUnit; }
    public double maintenanceCashPerEtuOr0() { return maintenanceCashPerEtu == null ? 0 : maintenanceCashPerEtu; }
}
