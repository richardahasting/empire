package org.hastingtx.empire.engine.config;

import java.util.List;

public record DistributionCfg(
        EconomyCfg.TechScaled maxReachSectors,
        String mobilityDebitedFrom,
        String partialDelivery,
        List<String> contention,
        boolean heldCargoCountsTowardCapacity,
        String pathCost,
        Double quantum) {
    /** 0 = continuous allocation (exactly symmetric); >0 = whole units, priority + RNG break the last one. */
    public double quantumOr0() { return quantum == null ? 0.0 : quantum; }
}
