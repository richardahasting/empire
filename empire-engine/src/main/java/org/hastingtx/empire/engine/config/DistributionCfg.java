package org.hastingtx.empire.engine.config;

import java.util.List;

public record DistributionCfg(
        EconomyCfg.TechScaled maxReachSectors,
        String mobilityDebitedFrom,
        String partialDelivery,
        List<String> contention,
        boolean heldCargoCountsTowardCapacity,
        String pathCost,
        Double quantum,
        /** Distribution moves goods this many times cheaper than a hand move (KNOWN: IMPORT/EXPORT_BONUS 10). */
        Double mobilityBonus) {
    /** True when the sector the goods leave from pays the whole route's mobility ({@code sending_sector}); false when every entered sector pays ({@code transited_sectors}). */
    public boolean sourcePays() { return "sending_sector".equals(mobilityDebitedFrom) || "source".equals(mobilityDebitedFrom); }
    public double mobilityBonusOr1() { return mobilityBonus == null || mobilityBonus <= 0 ? 1.0 : mobilityBonus; }
    /** 0 = continuous allocation (exactly symmetric); >0 = whole units, priority + RNG break the last one. */
    public double quantumOr0() { return quantum == null ? 0.0 : quantum; }
}
