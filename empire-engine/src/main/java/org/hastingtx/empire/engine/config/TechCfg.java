package org.hastingtx.empire.engine.config;

import java.util.Map;

public record TechCfg(Map<String, String> curves, Map<String, Integer> gates) {}
