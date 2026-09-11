package org.hastingtx.empire.server.game;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.Terrain;
import org.hastingtx.empire.engine.model.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #105: the parameters on the create-world form have to change the map, not just the YAML.
 * These go through the real preset, the real loader and the real generator.
 */
class WorldOverridesGenerateTest {

    private static final ConfigLoader LOADER = new ConfigLoader();
    private static final List<String> TWO = List.of("Rick", "Sharon");
    private static final long SEED = 20260911L;

    private static World generate(WorldOverrides o) {
        ConfigLoader.Loaded l = LOADER.loadPreset("classic");
        ConfigLoader.Loaded patched = o.empty() ? l : LOADER.loadYaml(LOADER.toYaml(o.patch(l.raw(), TWO.size())));
        return new WorldGenerator(patched.config()).generate(TWO, SEED);
    }

    private static WorldOverrides only(Integer w, Integer h, Double water, Integer island, Integer spike, Integer cap, Boolean wx, Boolean wy, Map<String, Double> mix) {
        return new WorldOverrides(w, h, water, island, spike, cap, wx, wy, mix, null);
    }

    private static long count(World w, Terrain t) {
        return w.sectors().stream().map(Sector::terrain).filter(x -> x == t).count();
    }

    private static long land(World w) {
        return w.sectors().stream().map(Sector::terrain).filter(Terrain::isLand).count();
    }

    @Test
    void sizeOverrideChangesTheMap() {
        World w = generate(only(48, 24, null, null, null, null, null, null, null));
        assertEquals(48, w.width());
        assertEquals(24, w.height());
    }

    @Test
    void lessWaterMeansMoreLand() {
        long wet = land(generate(only(null, null, 85.0, null, null, null, null, null, null)));
        long dry = land(generate(only(null, null, 40.0, null, null, null, null, null, null)));
        assertTrue(dry > wet * 2, "40% water should give far more land than 85%: " + dry + " vs " + wet);
    }

    @Test
    void aMountainHeavyMixActuallyBuildsMountains() {
        Map<String, Double> mountains = Map.of("mountain", 9.0, "plains", 1.0);
        World w = generate(only(null, null, null, null, null, null, null, null, mountains));
        long mountain = count(w, Terrain.MOUNTAIN), forest = count(w, Terrain.FOREST), swamp = count(w, Terrain.SWAMP);
        assertEquals(0, forest, "forest was left out of the mix");
        assertEquals(0, swamp, "swamp was left out of the mix");
        assertTrue(mountain > land(w) * 0.7, "most of the land should be mountain, got " + mountain + " of " + land(w));
    }

    @Test
    void theDefaultMixIsNotMountainHeavy() {
        World w = generate(WorldOverrides.NONE);
        assertTrue(count(w, Terrain.MOUNTAIN) < land(w) * 0.3, "the classic preset is mostly wilderness and plains");
    }

    @Test
    void turningOffTheWrapsMakesAWorldWithEdges() {
        World w = generate(only(null, null, null, null, null, null, false, false, null));
        assertFalse(w.wrapX());
        assertFalse(w.wrapY());
    }

    @Test
    void aBiggerIslandSizeMakesFewerBiggerLandmasses() {
        // same land budget, different parcelling: big islands leave more of the map untouched ocean
        World small = generate(only(64, 32, 70.0, 6, null, null, null, null, null));
        World big = generate(only(64, 32, 70.0, 120, null, null, null, null, null));
        assertTrue(islands(big) < islands(small), "islands: " + islands(big) + " big vs " + islands(small) + " small");
    }

    /** Connected components of land, counted on the offset grid without wrapping — good enough to compare two maps. */
    private static int islands(World w) {
        boolean[] seen = new boolean[w.width() * w.height()];
        int n = 0;
        for (int y = 0; y < w.height(); y++) for (int x = 0; x < w.width(); x++) {
            int i = y * w.width() + x;
            if (seen[i] || !w.sectors().get(i).terrain().isLand()) continue;
            n++;
            java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
            q.add(new int[]{x, y});
            seen[i] = true;
            while (!q.isEmpty()) {
                int[] c = q.poll();
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = c[0] + dx, ny = c[1] + dy;
                    if (nx < 0 || ny < 0 || nx >= w.width() || ny >= w.height()) continue;
                    int j = ny * w.width() + nx;
                    if (seen[j] || !w.sectors().get(j).terrain().isLand()) continue;
                    seen[j] = true;
                    q.add(new int[]{nx, ny});
                }
            }
        }
        return n;
    }
}
