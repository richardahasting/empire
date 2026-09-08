package org.hastingtx.empire.config;

import org.hastingtx.empire.engine.config.*;
import org.hastingtx.empire.engine.model.Terrain;

import java.util.*;

/** Cross-reference and range checks that type binding cannot express. Errors name the YAML path. */
public final class SchemaValidator {
    private SchemaValidator() {}

    public static void validate(GameConfig c) {
        List<String> errs = new ArrayList<>();
        WorldCfg w = c.world();
        if (w.width() < 4 || w.height() < 4) errs.add("world.width/height must be >= 4");
        if (w.wrapY() && (w.height() & 1) == 1) errs.add("world.height must be even when world.wrap_y is true");
        double mix = 0;
        for (var e : w.terrain().landMix().entrySet()) { terrain(e.getKey(), "world.terrain.land_mix", errs); mix += e.getValue(); }
        if (Math.abs(mix - 1.0) > 0.01) errs.add("world.terrain.land_mix must sum to 1.0 (is " + mix + ")");
        if (w.terrain().landFraction() <= 0 || w.terrain().landFraction() > 1) errs.add("world.terrain.land_fraction must be in (0,1]");
        if (c.schedule().etusPerUpdate() <= 0) errs.add("schedule.etus_per_update must be > 0");
        if (c.players().maxCountries() < 1) errs.add("players.max_countries must be >= 1");

        Set<String> com = new HashSet<>();
        for (CommodityCfg k : c.commodities()) if (!com.add(k.id())) errs.add("commodities: duplicate id " + k.id());
        for (String req : List.of("civ", "mil", "uw", "food")) if (!com.contains(req)) errs.add("commodities must include " + req);
        for (String t : c.terrain().keySet()) terrain(t, "terrain", errs);
        for (String t : c.economy().mobility().moveCostByTerrain().keySet()) terrain(t, "economy.mobility.move_cost_by_terrain", errs);
        for (var s : c.players().startingCommodities().capital().keySet()) if (!com.contains(s)) errs.add("players.starting_commodities.capital: unknown commodity " + s);
        for (var s : c.players().startingCommodities().sanctuary().keySet()) if (!com.contains(s)) errs.add("players.starting_commodities.sanctuary: unknown commodity " + s);

        Set<String> ids = new HashSet<>(), glyphs = new HashSet<>();
        Set<String> curves = c.economy().curves().keySet();
        Set<String> levels = Set.of("tech", "research", "education", "happiness");
        for (SectorTypeCfg t : c.economy().sectorTypes()) {
            String p = "economy.sector_types[" + t.id() + "]";
            if (!ids.add(t.id())) errs.add(p + ": duplicate id");
            if (t.glyph() == null || t.glyph().length() != 1) errs.add(p + ".glyph must be one character");
            else if (!glyphs.add(t.glyph())) errs.add(p + ".glyph '" + t.glyph() + "' is already used");
            for (String k : t.produces().keySet()) if (!com.contains(k)) errs.add(p + ".produces: unknown commodity " + k);
            for (String k : t.consumes().keySet()) if (!com.contains(k)) errs.add(p + ".consumes: unknown commodity " + k);
            for (String k : t.build().keySet()) if (!k.equals("cash") && !com.contains(k)) errs.add(p + ".build: unknown commodity " + k);
            for (String k : t.producesLevel().keySet()) if (!levels.contains(k)) errs.add(p + ".produces_level: unknown level " + k);
            if (t.resourceGate() != null && !Set.of("fertility", "minerals", "gold", "oil", "uranium").contains(t.resourceGate())) errs.add(p + ".resource_gate: unknown " + t.resourceGate());
            if (t.levelEffect() != null) {
                if (!levels.contains(t.levelEffect().level())) errs.add(p + ".level_effect.level: unknown " + t.levelEffect().level());
                if (!curves.contains(t.levelEffect().curve())) errs.add(p + ".level_effect.curve: unknown " + t.levelEffect().curve());
            }
            if (t.terrainRequired() != null) for (String tr : t.terrainRequired()) terrain(tr, p + ".terrain_required", errs);
            if (t.maxPopulation() < 0) errs.add(p + ".max_population must be >= 0");
        }
        for (String req : List.of("wilderness", "sanctuary", "capital")) if (!ids.contains(req)) errs.add("economy.sector_types must include " + req);
        var lv = c.economy().levels();
        if (lv.tech().logBase() <= 0 || lv.research().logBase() <= 0 || lv.education().logBase() <= 0 || lv.happiness().logBase() <= 0) errs.add("economy.levels.*.log_base must be > 0");
        if (lv.education().averageEtus() <= 0 || lv.happiness().averageEtus() <= 0) errs.add("economy.levels.{education,happiness}.average_etus must be > 0");
        for (var e : c.economy().curves().entrySet()) {
            try { e.getValue().eval(50); } catch (RuntimeException ex) { errs.add("economy.curves." + e.getKey() + ": " + ex.getMessage()); }
        }
        for (String x : c.distribution().contention()) if (!Set.of("proportional", "commodity_priority", "seeded_rng").contains(x)) errs.add("distribution.contention: unknown rule " + x);
        if (!Set.of("hold_in_place", "return_to_source").contains(c.distribution().partialDelivery())) errs.add("distribution.partial_delivery must be hold_in_place or return_to_source");
        if (c.distribution().quantum() != null && c.distribution().quantum() <= 0) errs.add("distribution.quantum must be > 0");
        HandicapCfg h = c.handicapDefaults();
        for (Double d : Arrays.asList(h.btuRate(), h.btuCap(), h.production(), h.mobility(), h.researchRate(), h.startingCommodities()))
            if (d == null || d < 0) errs.add("handicap_defaults: every multiplier must be present and >= 0");
        if (c.players().countries() != null) {
            Set<String> names = new HashSet<>();
            for (var pc : c.players().countries()) {
                if (!names.add(pc.name())) errs.add("players.countries: duplicate name " + pc.name());
                if (!Set.of("human", "agent").contains(pc.controller())) errs.add("players.countries[" + pc.name() + "].controller must be human or agent");
            }
        }
        if (!errs.isEmpty()) throw new ConfigException("invalid config:\n  - " + String.join("\n  - ", errs));
    }

    private static void terrain(String id, String path, List<String> errs) {
        try { Terrain.of(id); } catch (IllegalArgumentException e) { errs.add(path + ": unknown terrain " + id); }
    }
}
