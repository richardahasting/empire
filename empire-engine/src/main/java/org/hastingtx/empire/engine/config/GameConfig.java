package org.hastingtx.empire.engine.config;

import java.util.List;
import java.util.Map;

/**
 * The typed form of config/schema.yaml. Immutable. Built by empire-config (Jackson);
 * the engine never parses anything. Field names are the YAML keys in camelCase.
 *
 * <p>Sections the M0 engine does not read yet (detection, infrastructure.rail, capture,
 * agents, tech gates, units) are still bound so the loader rejects typos in them.
 */
public record GameConfig(
        int schemaVersion,
        WorldCfg world,
        ScheduleCfg schedule,
        PlayersCfg players,
        List<CommodityCfg> commodities,
        Map<String, TerrainCfg> terrain,
        EconomyCfg economy,
        DistributionCfg distribution,
        InfrastructureCfg infrastructure,
        CaptureCfg capture,
        DetectionCfg detection,
        TechCfg tech,
        UnitsCfg units,
        AgentsCfg agents,
        HandicapCfg handicapDefaults,
        ScoringCfg scoring,
        OptionsCfg options) {

    public int etus() { return schedule.etusPerUpdate(); }

    public CommodityCfg commodity(String id) {
        for (CommodityCfg c : commodities) if (c.id().equals(id)) return c;
        throw new IllegalArgumentException("unknown commodity: " + id);
    }

    public SectorTypeCfg sectorType(String id) {
        for (SectorTypeCfg s : economy.sectorTypes()) if (s.id().equals(id)) return s;
        throw new IllegalArgumentException("unknown sector type: " + id);
    }

    public boolean hasSectorType(String id) {
        for (SectorTypeCfg s : economy.sectorTypes()) if (s.id().equals(id)) return true;
        return false;
    }
}
