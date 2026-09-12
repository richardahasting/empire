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
            /** Which commodity a tank holds (issue #65). Null means petrol. */
            String fuelCommodity,
            boolean crews,
            /** Roles whose crew is civilians; everything else musters military (issue #66). */
            List<String> crewCivRoles,
            boolean autoUnloadInHarbor,
            /** Food a fishing boat of rate 1 at 100% makes per ETU per point of sea fertility. */
            double fishingFoodPerEtuPerFertilityPoint,
            /** Issue #112: ore per work-equivalent per ETU per point of a hex's nodules. */
            double miningOrePerEtuPerMineralPoint,
            /** Issue #69: a sail moves the ship now, from its own mobility pool. */
            /** Efficiency a hull loses each update at sea; harbours are exempt. */
            double seaWearPerUpdate,
            /** At or below this, a mission breaks off for home to refit. */
            double refitBelow,
            /** And stays in harbour until this — fully refitted, not nearly. */
            double refitResumeAt,
            boolean immediateSail,
            /** How many updates' worth of movement a hull may bank. */
            double mobilityCapUpdates,
            /** What a hex costs when ordered now rather than planned (issue #69). */
            double immediateSailMobilityMultiplier,
            /** Speed multiplier from the ship's tech: at_0 + per_tech_point × tech, capped at max. */
            SpeedTech speedTechMultiplier,
            /** The fishing mission: how far from home a boat roams, how far it hops between casts, when it turns for home. */
            FishingCfg fishing,
            FishingCfg mining,
            /** How far a ship sees, in hexes, unless its class says otherwise (issue #62: "the fog is lifted"). */
            Integer sight,
            List<ShipClassCfg> classes) {
        public int sightOf(ShipClassCfg cls) { return cls.sight() != null ? cls.sight() : sight != null ? sight : 2; }
        /** What goes in a tank (issue #65). */
        public String fuelId() { return fuelCommodity == null ? "pet" : fuelCommodity; }
        /** True when this class musters civilians rather than military (issue #66). */
        public boolean crewIsCivilian(ShipClassCfg cls) {
            return crewCivRoles == null ? !"warship".equals(cls.role()) && !"submarine".equals(cls.role()) : crewCivRoles.contains(cls.role());
        }
        public record FishingCfg(int radius, int wanderHops, double returnWhenHoldFraction) {}
        public FishingCfg fishingOrDefault() { return fishing == null ? new FishingCfg(6, 2, 0.9) : fishing; }
        /** The mining mission's shape; nodule fields are rarer, so it roams further by default. */
        public FishingCfg miningOrDefault() { return mining == null ? new FishingCfg(8, 2, 0.9) : mining; }
        public record SpeedTech(double at0, double perTechPoint, double max) {}
        public double speedFactor(double tech) {
            if (speedTechMultiplier == null) return 1.0;
            return Math.min(speedTechMultiplier.max(), speedTechMultiplier.at0() + speedTechMultiplier.perTechPoint() * tech);
        }
        /** Sea hexes per update for a ship of this class, tech and efficiency. */
        public int range(ShipClassCfg cls, double tech, double efficiency) { return (int) Math.floor(cls.speed() * speedFactor(tech) * efficiency / 100.0); }

        /** Mobility per hex for a sail ordered now; a planned deployment pays 1. */
        public double rushCost() { return immediateSailMobilityMultiplier <= 0 ? 1.0 : immediateSailMobilityMultiplier; }

        /** The most a hull may bank: idling does not make a ship arbitrarily fast (issue #69). */
        public double mobilityCap(ShipClassCfg cls, double tech, double efficiency) {
            double per = cls.speed() * speedFactor(tech) * efficiency / 100.0;
            return per * (mobilityCapUpdates <= 0 ? 1 : mobilityCapUpdates);
        }
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
            /** mining: multiplier on the seabed mining rate (issue #112). */
            Double miningRate,
            /** luxury: happiness produced per ETU at 100% while at sea. */
            Double happinessPerEtu,
            /** What it may carry: commodity ids, or "all" / "goods" / "people". Empty = nothing. */
            List<String> carries,
            Map<String, Double> upkeepPerUpdate,
            /** Sight radius in hexes; null = the fleet default. */
            Integer sight,
            /** How much fuel the hull holds (issue #65). Null on a class that predates fuel. */
            Double tank,
            /** Fuel burned per sea hex sailed. */
            Double fuelPerHex,
            /** How many people it takes to sail her (issue #66). */
            Double crew) {
        public double crewOr0() { return crew == null ? 0 : crew; }
        public double tankOr0() { return tank == null ? 0 : tank; }
        public double fuelPerHexOr0() { return fuelPerHex == null ? 0 : fuelPerHex; }
        public double fishingRateOr0() { return fishingRate == null ? 0 : fishingRate; }
        public double miningRateOr0() { return miningRate == null ? 0 : miningRate; }
        /** A hull that works the sea and comes home loaded, whichever thing it is working. */
        public boolean worksTheSea() { return fishingRateOr0() > 0 || miningRateOr0() > 0; }
        public double happinessOr0() { return happinessPerEtu == null ? 0 : happinessPerEtu; }
        public List<String> carriesOrEmpty() { return carries == null ? List.of() : carries; }
        public Map<String, Double> buildOrEmpty() { return build == null ? Map.of() : build; }
    }
}
