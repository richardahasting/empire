package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.Terrain;
import org.hastingtx.empire.engine.model.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #81: a game may be created with its own map size and water fraction, overriding the preset.
 * This covers the config side of it — patch the raw map, rebind, generate — which is what
 * GameService.withMap does before it hands the config to the generator.
 */
class WorldSizeAndWaterTest {

    /** Reload a preset with width, height and land_fraction overridden, as GameService.withMap does. */
    @SuppressWarnings("unchecked")
    private static GameConfig withMap(String preset, int width, int height, Double waterPercent) {
        ConfigLoader loader = new ConfigLoader();
        ConfigLoader.Loaded l = loader.loadPreset(preset);
        var raw = new java.util.LinkedHashMap<>(l.raw());
        var world = new java.util.LinkedHashMap<>((Map<String, Object>) raw.get("world"));
        world.put("width", width);
        world.put("height", height);
        if (waterPercent != null) {
            var terrain = new java.util.LinkedHashMap<>((Map<String, Object>) world.get("terrain"));
            terrain.put("land_fraction", Math.round((1.0 - waterPercent / 100.0) * 1000.0) / 1000.0);
            world.put("terrain", terrain);
        }
        raw.put("world", world);
        return loader.loadYaml(loader.toYaml(raw)).config();
    }

    private static long landCount(World w) {
        return w.sectors().stream().filter(s -> s.terrain() != Terrain.OCEAN).count();
    }

    @Test
    void theWorldIsTheSizeItWasAskedFor() {
        GameConfig cfg = withMap("teaching", 48, 32, null);
        assertThat(cfg.world().width()).isEqualTo(48);
        assertThat(cfg.world().height()).isEqualTo(32);
        World w = new WorldGenerator(cfg).generate(List.of("A", "B"), 7);
        assertThat(w.width()).isEqualTo(48);
        assertThat(w.height()).isEqualTo(32);
        assertThat(w.sectors()).hasSize(48 * 32);
    }

    @Test
    void moreWaterMeansLessLand() {
        // PROBE: a water setting that silently did nothing would satisfy every other assertion here.
        // Same size, same seed — only the water differs, so the land must differ with it.
        World dry = new WorldGenerator(withMap("teaching", 64, 64, 30.0)).generate(List.of("A", "B"), 11);
        World wet = new WorldGenerator(withMap("teaching", 64, 64, 80.0)).generate(List.of("A", "B"), 11);
        assertThat(landCount(wet)).as("80%% water leaves less land than 30%%").isLessThan(landCount(dry));
        assertThat(landCount(dry)).isPositive();
        assertThat(landCount(wet)).as("even a wet world keeps land for its capitals").isPositive();
    }

    @Test
    void theChosenMapSurvivesIntoTheStoredConfig() {
        // what GameService writes to the game row is this YAML, so the game plays by it for life
        ConfigLoader loader = new ConfigLoader();
        GameConfig reloaded = loader.loadYaml(loader.toYaml(
                new java.util.LinkedHashMap<>(loader.loadPreset("teaching").raw()) {{
                    var world = new java.util.LinkedHashMap<>((Map<String, Object>) get("world"));
                    world.put("width", 96); world.put("height", 80);
                    var terrain = new java.util.LinkedHashMap<>((Map<String, Object>) world.get("terrain"));
                    terrain.put("land_fraction", 0.15);
                    world.put("terrain", terrain);
                    put("world", world);
                }})).config();
        assertThat(reloaded.world().width()).isEqualTo(96);
        assertThat(reloaded.world().height()).isEqualTo(80);
        assertThat(reloaded.world().terrain().landFraction()).isEqualTo(0.15);
    }
}
