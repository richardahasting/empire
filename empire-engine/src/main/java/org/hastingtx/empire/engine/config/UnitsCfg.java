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
            Double miningOrePerEtuPerMineralPoint,
            /** Issue #69: a sail moves the ship now, from its own mobility pool. */
            /** Efficiency a hull loses each update at sea; harbours are exempt. */
            Double seaWearPerUpdate,
            /** Sea wear stops at the efficiency a hull needs to make one hex an update, so she can always limp home. */
            Boolean seaWearLimpHome,
            /** At or below this, a mission breaks off for home to refit. */
            Double refitBelow,
            /** And stays in harbour until this — fully refitted, not nearly. */
            Double refitResumeAt,
            Boolean immediateSail,
            /** How many updates' worth of movement a hull may bank. */
            Double mobilityCapUpdates,
            /** What a hex costs when ordered now rather than planned (issue #69). */
            Double immediateSailMobilityMultiplier,
            /** Speed multiplier from the ship's tech: at_0 + per_tech_point × tech, capped at max. */
            SpeedTech speedTechMultiplier,
            /** The fishing mission: how far from home a boat roams, how far it hops between casts, when it turns for home. */
            FishingCfg fishing,
            FishingCfg mining,
            /** How far a ship sees, in hexes, unless its class says otherwise (issue #62: "the fog is lifted"). */
            Integer sight,
            List<ShipClassCfg> classes,
            /** Gunnery, sinking and salvage (issue #68). Null in a game that predates combat: nothing fires. */
            CombatCfg combat,
            /** Military missions (issue #68): when to come home for supplies, how a search roams, what a blockade reaches. */
            MissionsCfg missions) {
        public MissionsCfg missionsOrDefault() { return missions == null ? MissionsCfg.NONE : missions; }
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
        /*
         * Everything below reads a boxed field and supplies a default.
         *
         * A game keeps its own copy of the rules for life, so a snapshot written before a setting
         * existed has no value for it — and a primitive cannot hold "absent". The loader binds
         * strictly, by design, so a new primitive field stops every older game from loading at all:
         * GameService.loadAll catches per game and logs, which makes the failure a world quietly
         * missing rather than a crash. That is how this was found (issue #69, in production).
         *
         * So: a field added after any game could have been created is boxed, and defaults here.
         */
        public double rushCost() { return immediateSailMobilityMultiplier == null || immediateSailMobilityMultiplier <= 0 ? 1.0 : immediateSailMobilityMultiplier; }

        /** Ore per ETU per point of nodules; 0 when the game predates seabed mining. */
        public double oreRate() { return miningOrePerEtuPerMineralPoint == null ? 0 : miningOrePerEtuPerMineralPoint; }

        /**
         * The least efficiency sea wear leaves a hull of this class and tech (Richard 2026-09-13, "limp
         * home"): enough to make one hex an update, so a ship sailed by hand and forgotten can still
         * crawl back to a harbour instead of being stranded for good. 0 when the rule is off.
         *
         * <p>Unlike the settings above, an absent key means ON. This corrects a trap rather than adding a
         * rule — game 82 had two miners worn to 4% and 0%, one with a full hold, that no order could ever
         * move again — so a game that predates the key should get the fix, not keep the trap.
         */
        public double limpFloor(ShipClassCfg cls, double tech) {
            if (seaWearLimpHome != null && !seaWearLimpHome) return 0;
            double perHexAt100 = cls.speed() * speedFactor(tech);
            return perHexAt100 <= 0 ? 0 : Math.min(100, Math.ceil(100.0 / perHexAt100));
        }

        /** Efficiency lost per update at sea; 0 when the game predates the rule. */
        public double seaWear() { return seaWearPerUpdate == null ? 0 : seaWearPerUpdate; }

        /** Break off below this; 0 disables it, which is what an older game gets. */
        public double refitAtOrBelow() { return refitBelow == null ? 0 : refitBelow; }

        /** Stay in until this. */
        public double refitUpTo() { return refitResumeAt == null ? 100 : refitResumeAt; }

        /** Whether a sail moves the ship now; older games keep the update-time behaviour they had. */
        public boolean immediate() { return immediateSail != null && immediateSail; }

        /** The most a hull may bank: idling does not make a ship arbitrarily fast (issue #69). */
        public double mobilityCap(ShipClassCfg cls, double tech, double efficiency) {
            double per = cls.speed() * speedFactor(tech) * efficiency / 100.0;
            double caps = mobilityCapUpdates == null || mobilityCapUpdates <= 0 ? 1 : mobilityCapUpdates;
            return per * caps;
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
            Double crew,
            /** Guns it can bring to bear in one salvo (issue #68); null or 0 = unarmed. */
            Double guns,
            /** Firing range in hexes. */
            Integer range,
            /** Armour: damage taken is divided by 1 + armour/100. */
            Double armor,
            /** Shells it carries when rearmed in harbour. */
            Double magazine,
            /** Hull efficiency points one gun does; null = the combat default. A torpedo hits harder than a gun. */
            Double hitPerGun,
            /** Can find and fire on a submarine it has detected. */
            Boolean asw) {
        public double gunsOr0() { return guns == null ? 0 : guns; }
        public boolean armed() { return gunsOr0() > 0; }
        public int rangeOr0() { return range == null ? 0 : range; }
        public double armorOr0() { return armor == null ? 0 : armor; }
        public double magazineOr0() { return magazine == null ? 0 : magazine; }
        public boolean aswOrFalse() { return asw != null && asw; }
        public boolean submarine() { return "submarine".equals(role); }
        /** A fighting hull: it does not run cargo on a lane or a supply round. */
        public boolean military() { return "warship".equals(role) || submarine(); }
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

    /**
     * Sea combat (issue #68). Every number a GUESS to tune. All of it boxed: a game created before
     * combat has none of these keys, and binds with {@code combat} null — which means nothing fires.
     */
    public record CombatCfg(
            /** Shells one gun uses per salvo. */
            Double shellsPerGun,
            /** Hull efficiency points one gun does at 100% efficiency, tech 0, against no armour. */
            Double damagePerGun,
            /** The seeded roll multiplies damage by a value in [min, max]. */
            Roll damageRoll,
            /** Damage × (1 + this × the firer's tech), up to {@code techBonusMax} extra. */
            Double techBonusPerPoint,
            Double techBonusMax,
            /** A hull at or below this efficiency sinks. */
            Double sinkAtOrBelow,
            /** How many updates the ships that fired on a country at peace stay fair game to it. */
            Integer firedUponUpdates,
            /** A contact older than this many updates is not good enough to aim at. */
            Integer targetMaxAge,
            /** Sectors that defend the coast: which designations, how far they reach, how many guns they may bring, military per gun. */
            Coastal coastal) {
        public record Roll(double min, double max) {}
        public record Coastal(List<String> designations, Integer range, Double maxGuns, Double milPerGun) {
            public List<String> designationsOrEmpty() { return designations == null ? List.of() : designations; }
            public int rangeOr0() { return range == null ? 0 : range; }
            public double maxGunsOr0() { return maxGuns == null ? 0 : maxGuns; }
            public double milPerGunOr1() { return milPerGun == null || milPerGun <= 0 ? 1 : milPerGun; }
        }
        public double shellsPerGunOr1() { return shellsPerGun == null ? 1 : shellsPerGun; }
        public double damagePerGunOr0() { return damagePerGun == null ? 0 : damagePerGun; }
        public double rollMin() { return damageRoll == null ? 1 : damageRoll.min(); }
        public double rollMax() { return damageRoll == null ? 1 : damageRoll.max(); }
        public double techFactor(double tech) {
            double per = techBonusPerPoint == null ? 0 : techBonusPerPoint, max = techBonusMax == null ? 0 : techBonusMax;
            return 1.0 + Math.min(max, per * Math.max(0, tech));
        }
        public double sinkAt() { return sinkAtOrBelow == null ? 0 : sinkAtOrBelow; }
        public int grudgeUpdates() { return firedUponUpdates == null ? 0 : firedUponUpdates; }
        public int maxTargetAge() { return targetMaxAge == null ? 0 : targetMaxAge; }
        public Coastal coastalOrNone() { return coastal == null ? new Coastal(List.of(), 0, 0.0, 1.0) : coastal; }
    }

    /**
     * Military missions (issue #68). Boxed throughout; a game with none of these keys gets the defaults
     * in the accessors, which only matter once it also has combat.
     */
    public record MissionsCfg(
            /** Come home when shells fall below this fraction of the magazine. */
            Double resupplyShellsBelow,
            /** ...or fuel below this fraction of the tank. */
            Double resupplyFuelBelow,
            /** ...or below the fuel it takes to get home, times this. */
            Double fuelReserveFactor,
            /** A search roams within this of home, hopping at most this far between legs. */
            Integer searchRadius,
            Integer searchHops,
            /** A hostile ship this close to a blockading warship on station is stopped where she is. */
            Integer blockadeRadius,
            /** Units of a train's cargo one gun destroys. */
            Double trainUnitsPerGun) {
        public static final MissionsCfg NONE = new MissionsCfg(null, null, null, null, null, null, null);
        public double shellsBelow() { return resupplyShellsBelow == null ? 0 : resupplyShellsBelow; }
        public double fuelBelow() { return resupplyFuelBelow == null ? 0 : resupplyFuelBelow; }
        public double reserve() { return fuelReserveFactor == null ? 1 : fuelReserveFactor; }
        public int searchRadiusOr0() { return searchRadius == null ? 0 : searchRadius; }
        public int searchHopsOr0() { return searchHops == null ? 0 : searchHops; }
        public int blockadeRadiusOr0() { return blockadeRadius == null ? 0 : blockadeRadius; }
        public double trainUnitsPerGunOr0() { return trainUnitsPerGun == null ? 0 : trainUnitsPerGun; }
    }
}
