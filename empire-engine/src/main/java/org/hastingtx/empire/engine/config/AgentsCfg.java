package org.hastingtx.empire.engine.config;

import java.util.Map;

public record AgentsCfg(
        int turnWindowSeconds,
        int retries,
        int contextMaxTokens,
        int scratchpadMaxChars,
        Integer sessionWindowsPerDayDefault,
        Map<String, Map<String, String>> adapters,
        boolean diplomacy) {}
