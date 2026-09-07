package org.hastingtx.empire.engine.config;

/** {@code isPerson} is optional in YAML (absent = false). */
public record CommodityCfg(
        String id,
        String name,
        double weight,
        double signature,
        int priority,
        Boolean isPerson) {
    public boolean person() { return Boolean.TRUE.equals(isPerson); }
}
