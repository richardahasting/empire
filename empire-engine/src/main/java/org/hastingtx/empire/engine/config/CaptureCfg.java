package org.hastingtx.empire.engine.config;

public record CaptureCfg(
        double cargoDestroyedFraction,
        double stockDestroyedFraction,
        double scorchedEarthBtuCost,
        double scorchedEarthDestroyFraction,
        /** Taking held coast from the sea (issue #206). Null in a game that predates it: assault is refused. */
        AssaultCfg assault) {

    /**
     * Man for man, as the original fought it (Richard 2026-09-14). Boxed: a game from before assault
     * binds with none of these keys.
     */
    public record AssaultCfg(
            /** A soldier in a 0% sector (or off a 0% ship) fights at this fraction of one in a 100% one. */
            Double efficiencyFloor,
            /** Each defender in a sector whose type has the {@code defense_bonus} flag counts this many times. */
            Double fortBonus,
            /** The defender's military in the sectors next to the one assaulted join the fight. */
            Boolean neighboursDefend) {
        public double floor() { return efficiencyFloor == null ? 0.5 : efficiencyFloor; }
        public double fort() { return fortBonus == null ? 1 : fortBonus; }
        public boolean neighbours() { return neighboursDefend != null && neighboursDefend; }
        /** What one soldier is worth at this efficiency. */
        public double perMan(double efficiency) { return floor() + (1 - floor()) * Math.max(0, Math.min(100, efficiency)) / 100.0; }
    }
}
