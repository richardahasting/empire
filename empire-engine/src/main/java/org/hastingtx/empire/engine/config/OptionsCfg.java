package org.hastingtx.empire.engine.config;

public record OptionsCfg(
        boolean fallout,
        boolean plague,
        boolean loans,
        boolean market,
        boolean hidden,
        boolean scorchedEarth,
        boolean sanctuary,
        boolean interest,
        boolean demandUpdate,
        /**
         * Store a hash of the whole world in the update log every update (issue #82). Off by default:
         * nothing reads it, and hashing a million-sector world costs about 9.5 seconds an update. The
         * world is persisted anyway, so the hash can be recomputed from any saved state when wanted.
         * Turn it on for a small game where you want per-update divergence detection for free.
         */
        Boolean stateHashPerUpdate) {

    public boolean stateHash() { return Boolean.TRUE.equals(stateHashPerUpdate); }
}
