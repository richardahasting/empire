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
        Boolean stateHashPerUpdate,
        /**
         * Keep a per-country map of everything ever seen (issue #64). On by default: the original did
         * this, and without it the fog closes behind a ship and a coastline you sailed past is gone from
         * your chart as though you had never been. Off is the pre-#64 behaviour, and cheaper — the
         * memory is world state, so it is saved, loaded and hashed with everything else.
         */
        Boolean mapMemory) {

    public boolean stateHash() { return Boolean.TRUE.equals(stateHashPerUpdate); }

    /** Defaults on: a chart you cannot keep is not a chart. */
    public boolean mapMemoryOn() { return !Boolean.FALSE.equals(mapMemory); }
}
