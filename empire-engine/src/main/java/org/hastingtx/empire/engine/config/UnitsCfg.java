package org.hastingtx.empire.engine.config;

import java.util.List;
import java.util.Map;

/** Units. Phase 1 (issue #56): ships. {@code table} is the M5 land/air table, not read yet. */
public record UnitsCfg(boolean enabled, String table, ShipsCfg ships) {

    public record ShipsCfg(
            /** A new hull leaves the yard at this efficiency (KNOWN: 20). */
            double startEfficiency,
            /** Efficiency a docked hull gains per update in a working harbour of yours. */
            double dockPointsPerUpdate,
            /** Per point of dock work: materials from the harbour's stock and cash. */
            Map<String, Double> dockMaterialsPerPoint,
            /** Harbour efficiency needed to build or repair. */
            double harborMinEfficiency,
            boolean fuel,
            boolean crews,
            boolean autoUnloadInHarbor,
            /** Food a fishing boat of rate 1 at 100% makes per ETU per point of sea fertility. */
            double fishingFoodPerEtuPerFertilityPoint,
            /** Speed multiplier from the ship's tech: at_0 + per_tech_point × tech, capped at max. */
            SpeedTech speedTechMultiplier,
            /** The fishing mission: how far from home a boat roams, how far it hops between casts, when it turns for home. */
            FishingCfg fishing,
            List<ShipClassCfg> classes) {
        public record FishingCfg(int radius, int wanderHops, double returnWhenHoldFraction) {}
        public FishingCfg fishingOrDefault() { return fishing == null ? new FishingCfg(6, 2, 0.9) : fishing; }
        public record SpeedTech(double at0, double perTechPoint, double max) {}
        public double speedFactor(double tech) {
            if (speedTechMultiplier == null) return 1.0;
            return Math.min(speedTechMultiplier.max(), speedTechMultiplier.at0() + speedTechMultiplier.perTechPoint() * tech);
        }
        /** Sea hexes per update for a ship of this class, tech and efficiency. */
        public int range(ShipClassCfg cls, double tech, double efficiency) { return (int) Math.floor(cls.speed() * speedFactor(tech) * efficiency / 100.0); }
        public ShipClassCfg shipClass(String id) {
            for (ShipClassCfg c : classes) if (c.id().equals(id)) return c;
            throw new IllegalArgumentException("unknown ship class: " + id);
        }
        public boolean hasClass(String id) { for (ShipClassCfg c : classes) if (c.id().equals(id)) return true; return false; }
    }

    /** One row of the ship table. {@code role}: fishing | cargo | tanker | luxury | warship | submarine. */
    public record ShipClassCfg(
            String id,
            String name,
            String glyph,
            String role,
            int techRequired,
            Map<String, Double> build,
            double hold,
            /** Sea hexes per update at 100% efficiency. */
            double speed,
            /** fishing: multiplier on the fishing rate. */
            Double fishingRate,
            /** luxury: happiness produced per ETU at 100% while at sea. */
            Double happinessPerEtu,
            /** What it may carry: commodity ids, or "all" / "goods" / "people". Empty = nothing. */
            List<String> carries,
            Map<String, Double> upkeepPerUpdate) {
        public double fishingRateOr0() { return fishingRate == null ? 0 : fishingRate; }
        public double happinessOr0() { return happinessPerEtu == null ? 0 : happinessPerEtu; }
        public List<String> carriesOrEmpty() { return carries == null ? List.of() : carries; }
        public Map<String, Double> buildOrEmpty() { return build == null ? Map.of() : build; }
    }
}
