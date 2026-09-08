package org.hastingtx.empire.engine.config;

public record ScheduleCfg(
        String updateInterval,
        int etusPerUpdate,
        String firstUpdateAt,
        boolean pauseOnFailedUpdate,
        int maxUpdates) {}
