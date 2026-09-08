package org.hastingtx.empire.engine.config;

import java.util.List;
import java.util.Map;

public record EconomyCfg(
        PopulationCfg population,
        WorkCfg work,
        double defaultCapacity,
        List<SectorTypeCfg> sectorTypes,
        Map<String, CurveCfg> curves,
        EfficiencyCfg efficiency,
        MobilityCfg mobility,
        MoneyCfg money,
        LevelsCfg levels,
        BtuCfg btu) {

    public record PopulationCfg(
            double foodPerCivPerEtu,
            double foodPerMilPerEtu,
            double foodPerUwPerEtu,
            double civBirthRatePerEtu,
            double uwBirthRatePerEtu,
            double foodPerBirth,
            double starvationMaxFractionPerUpdate,
            /** Who starves first (KNOWN: uw, civ, mil). */
            java.util.List<String> starvationOrder,
            /** Births are limited to food / (factor × food_per_birth) (KNOWN: 2). */
            Double birthFoodReserveFactor,
            /** Population cap scales with efficiency? KNOWN: no, except big cities. */
            Boolean maxPopScalesWithEfficiency,
            MaxPopCurve maxPopResearchCurve,
            PlagueCfg plague,
            SubsistenceCfg subsistence) {
        /** People who live off the land: no food consumed, no starvation, up to the limit. */
        public record SubsistenceCfg(double civsPerSector, boolean scaleByFertility, java.util.List<String> appliesTo) {
            public double limit(int fertility) { return scaleByFertility ? civsPerSector * fertility / 100.0 : civsPerSector; }
        }
        public SubsistenceCfg subsistenceOrNone() { return subsistence != null ? subsistence : new SubsistenceCfg(0, false, java.util.List.of()); }
        /** type "none" = flat; "res_pop" = the original's RES_POP: 0.4 + 0.6 × (50 + 4r)/(200 + 3r). */
        public record MaxPopCurve(String type, Double base, Double perResearchPoint, Double cap) {
            public double eval(double research) {
                if (type == null || type.equals("none")) return 1.0;
                if (type.equals("res_pop")) return 0.4 + 0.6 * (50.0 + 4.0 * research) / (200.0 + 3.0 * research);
                double b = base == null ? 1 : base, per = perResearchPoint == null ? 0 : perResearchPoint, c = cap == null ? 2 : cap;
                return Math.min(c, b + per * research);
            }
        }
        public java.util.List<String> starvationOrderOrDefault() { return starvationOrder == null ? java.util.List.of("uw", "civ", "mil") : starvationOrder; }
        public double birthFoodReserveFactorOr2() { return birthFoodReserveFactor == null ? 2.0 : birthFoodReserveFactor; }
        public record PlagueCfg(double baseProbabilityPerEtu, double crowdingExponent, double mortality, int durationUpdates) {}
    }

    public record WorkCfg(double perCiv, double perUw, double perMil, HappinessEffect happinessEffectCurve) {
        public record HappinessEffect(double neutralAt, double slopePerPoint, double min, double max) {
            public double eval(double happiness) {
                return Math.max(min, Math.min(max, 1.0 + (happiness - neutralAt) * slopePerPoint));
            }
        }
    }

    public record EfficiencyCfg(
            double maxPointsPerEtu,
            double workPerPoint,
            /** Fraction of a sector's work that may go to construction each update (KNOWN: 1/2). */
            Double buildWorkShare,
            /** Production needs at least this efficiency (KNOWN: 60). */
            Double productionMinEfficiency,
            RedesignateCfg redesignate,
            double decayPerUpdateIfUnowned) {
        public record RedesignateCfg(
                double keepFractionDefault,
                double keepFractionSameCategory,
                Map<String, Double> keepFractionByPair,
                double minEfficiencyAfter) {}
    }

    public record MobilityCfg(
            double sectorAccrualPerEtu,
            double sectorMax,
            boolean accrualEfficiencyScaled,
            Double accrualEfficiencyFloor,
            Map<String, Double> moveCostByTerrain,
            EfficiencyDiscount efficiencyDiscount,
            TechScaled manualMoveMaxSectorsPerUpdate) {
        /** YAML keys at_0 / at_100; the loader strips the underscore before a digit. */
        public double accrualFactor(double eff) {
            if (!accrualEfficiencyScaled) return 1.0;
            double floor = accrualEfficiencyFloor == null ? 0.0 : accrualEfficiencyFloor;
            return floor + (1.0 - floor) * Math.max(0, Math.min(100, eff)) / 100.0;
        }
        public record EfficiencyDiscount(double at0, double at100, String curve) {
            public double eval(double eff) { return at0 + (at100 - at0) * Math.max(0, Math.min(100, eff)) / 100.0; }
        }
    }

    /** base + per_tech_point * tech, used for several range-like quantities. */
    public record TechScaled(double base, double perTechPoint, Double roadBonusAt100) {
        public double eval(double tech) { return base + perTechPoint * tech; }
    }

    public record MoneyCfg(
            double taxPerCivPerEtu,
            double taxPerUwPerEtu,
            double payPerMilPerEtu,
            double sectorMaintenancePerEtu,
            double bankInterestPerBarPerEtu,
            BankruptcyCfg bankruptcy) {
        public record BankruptcyCfg(double threshold, Effect effect) {
            public record Effect(double productionMultiplier, double mobilityMultiplier) {}
        }
    }

    /**
     * KNOWN (update/nat.c, age.c). Tech and research are stocks: each update's production passes
     * limit_level (gain = easy + log_base(prod - easy + 1) above easy, cap 250) and is added; both
     * age 1% per level_age_rate ETUs. Education and happiness are moving averages of a per-ETU
     * rate: rate = produced × consumption / (civilians × ETUs) (happiness also × hap_edu(E) =
     * 1.5 - (E+10)/(E+20)), limited with the flag-1 curve, then level = (level × avg + rate × ETUs)
     * / (avg + ETUs). Technology bleed: below best/5, a 20% chance to gain (best/5 - level)/3.
     */
    public record LevelsCfg(
            StockLevel tech,
            StockLevel research,
            AverageLevel education,
            AverageLevel happiness,
            double levelAgeRate,
            Boolean techBleed,
            Double happinessRequirementTechDivisor,
            Double happinessRequirementEducationDivisor) {
        public record StockLevel(double easy, double logBase, double start) {}
        public record AverageLevel(double easy, double logBase, double consumption, double averageEtus, double start) {}

        static double logx(double d, double base) { return base == 1.0 ? d : Math.log10(d) / Math.log10(base); }

        /** limit_level(): flag=false for tech/research (log), flag=true for education/happiness. */
        public static double limit(double level, double easy, double logBase, boolean flag) {
            if (level <= easy) return level;
            double aboveEasy = level - easy;
            double above = flag ? aboveEasy / logx(logBase + aboveEasy, logBase) : logx(aboveEasy + 1.0, logBase);
            if (above > 250) above = 250;
            return above < 0 ? easy : easy + above;
        }
    }

    public record BtuCfg(
            double accrualPerCapitalCivPerEtu,
            /** KNOWN: the original multiplies by efficiency in percent and caps counted civilians at 1000. */
            Boolean scaleByEfficiencyPercent,
            Double capitalCivCap,
            double max,
            double start,
            Map<String, Integer> costByCommand) {
        public int cost(String command) {
            Integer c = costByCommand.get(command);
            return c != null ? c : costByCommand.getOrDefault("default", 1);
        }
    }
}
