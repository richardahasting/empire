package org.hastingtx.empire.engine.config;

public record CaptureCfg(
        double cargoDestroyedFraction,
        double stockDestroyedFraction,
        double scorchedEarthBtuCost,
        double scorchedEarthDestroyFraction) {}
