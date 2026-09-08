package org.hastingtx.empire.engine.config;

import java.util.Map;

/**
 * {@code isPerson} is optional in YAML (absent = false). {@code weight} is the original's lbs;
 * {@code packing} divides it by packing class of the sector the goods leave (KNOWN: item.config
 * pkg columns: inefficient, normal, warehouse, urban, bank). Absent = 1 for every class.
 */
public record CommodityCfg(
        String id,
        String name,
        double weight,
        double signature,
        int priority,
        Boolean isPerson,
        Map<String, Double> packing) {
    public boolean person() { return Boolean.TRUE.equals(isPerson); }
    public double pack(String cls) {
        if (packing == null) return 1.0;
        Double v = packing.get(cls);
        return v == null || v <= 0 ? 1.0 : v;
    }
}
