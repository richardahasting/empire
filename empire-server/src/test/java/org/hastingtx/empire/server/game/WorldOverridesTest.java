package org.hastingtx.empire.server.game;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Issue #105: the create-world form's parameters, as they land in the stored YAML. */
class WorldOverridesTest {

    private static Map<String, Object> preset() {
        Map<String, Object> terrain = new LinkedHashMap<>(Map.of(
                "land_fraction", 0.30, "island_size", 25, "spike", 10, "min_distance_between_capitals", 12,
                "land_mix", new LinkedHashMap<>(Map.of("wilderness", 0.40, "plains", 0.30, "forest", 0.18, "mountain", 0.10, "swamp", 0.02))));
        Map<String, Object> world = new LinkedHashMap<>(Map.of(
                "name", "Classic", "width", 128, "height", 64, "wrap_x", true, "wrap_y", true, "terrain", terrain));
        return new LinkedHashMap<>(Map.of("world", world, "schedule", new LinkedHashMap<>(Map.of("etus_per_update", 60))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> terrainOf(Map<String, Object> raw) {
        return (Map<String, Object>) ((Map<String, Object>) raw.get("world")).get("terrain");
    }

    private static WorldOverrides of(Integer w, Integer h, Double water, Integer island, Integer spike, Integer cap, Boolean wx, Boolean wy, Map<String, Double> mix) {
        return new WorldOverrides(w, h, water, island, spike, cap, wx, wy, mix);
    }

    @Test
    void anEmptyOverrideIsTheIdentity() {
        Map<String, Object> raw = preset();
        assertSame(raw, WorldOverrides.NONE.patch(raw, 4));
    }

    @Test
    void patchingDoesNotMutateTheInput() {
        Map<String, Object> raw = preset();
        of(64, 32, null, null, null, null, null, null, null).patch(raw, 2);
        assertEquals(128, ((Map<?, ?>) raw.get("world")).get("width"), "the preset the patch was built from must be untouched");
    }

    @Test
    void untouchedFieldsKeepThePresetValue() {
        Map<String, Object> t = terrainOf(of(null, null, null, 40, null, null, null, null, null).patch(preset(), 2));
        assertEquals(40, t.get("island_size"));
        assertEquals(10, t.get("spike"), "spike was not overridden");
        assertEquals(0.30, t.get("land_fraction"), "land fraction was not overridden");
    }

    @Test
    void waterPercentBecomesLandFraction() {
        assertEquals(0.25, terrainOf(of(null, null, 75.0, null, null, null, null, null, null).patch(preset(), 2)).get("land_fraction"));
    }

    @Test
    void landMixIsNormalisedToFractions() {
        Map<String, Double> weights = Map.of("wilderness", 10.0, "plains", 10.0, "forest", 10.0, "mountain", 10.0, "swamp", 10.0);
        @SuppressWarnings("unchecked")
        Map<String, Double> mix = (Map<String, Double>) terrainOf(of(null, null, null, null, null, null, null, null, weights).patch(preset(), 2)).get("land_mix");
        assertEquals(5, mix.size());
        assertEquals(1.0, mix.values().stream().mapToDouble(Double::doubleValue).sum(), 0.005);
        for (double v : mix.values()) assertEquals(0.2, v, 0.001);
    }

    @Test
    void aTerrainLeftOutOfTheMixGetsNoLand() {
        @SuppressWarnings("unchecked")
        Map<String, Double> mix = (Map<String, Double>) terrainOf(
                of(null, null, null, null, null, null, null, null, Map.of("plains", 1.0)).patch(preset(), 2)).get("land_mix");
        assertEquals(1.0, mix.get("plains"), 0.001);
        assertEquals(0.0, mix.get("mountain"), 0.001);
    }

    @Test
    void anAllZeroLandMixIsRejected() {
        Map<String, Double> zero = Map.of("wilderness", 0.0, "plains", 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> of(null, null, null, null, null, null, null, null, zero).patch(preset(), 2));
    }

    @Test
    void anUnknownTerrainIsRejected() {
        Map<String, Double> bad = Map.of("tundra", 1.0);
        assertThrows(IllegalArgumentException.class,
                () -> of(null, null, null, null, null, null, null, null, bad).patch(preset(), 2));
    }

    @Test
    void wrapsAreWrittenThrough() {
        @SuppressWarnings("unchecked")
        Map<String, Object> world = (Map<String, Object>) of(null, null, null, null, null, null, false, false, null).patch(preset(), 2).get("world");
        assertEquals(false, world.get("wrap_x"));
        assertEquals(false, world.get("wrap_y"));
    }

    @Test
    void anOddHeightIsRejectedWhileTheWorldWrapsNorthSouth() {
        // the engine throws on this at generation time; we want the 400, not the 500
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> of(null, 33, null, null, null, null, null, null, null).patch(preset(), 2));
        assertTrue(e.getMessage().contains("even"), e.getMessage());
    }

    @Test
    void anOddHeightIsFineOnceTheNorthSouthWrapIsOff() {
        @SuppressWarnings("unchecked")
        Map<String, Object> world = (Map<String, Object>) of(null, 33, null, null, null, null, null, false, null).patch(preset(), 2).get("world");
        assertEquals(33, world.get("height"));
    }

    @Test
    void outOfRangeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> of(8, null, null, null, null, null, null, null, null).patch(preset(), 2), "too small");
        assertThrows(IllegalArgumentException.class, () -> of(4096, null, null, null, null, null, null, null, null).patch(preset(), 2), "too wide");
        assertThrows(IllegalArgumentException.class, () -> of(2048, 2048, null, null, null, null, null, null, null).patch(preset(), 2), "too many sectors");
        assertThrows(IllegalArgumentException.class, () -> of(null, null, 99.0, null, null, null, null, null, null).patch(preset(), 2), "no room for capitals");
        assertThrows(IllegalArgumentException.class, () -> of(null, null, null, 2, null, null, null, null, null).patch(preset(), 2), "island too small");
        assertThrows(IllegalArgumentException.class, () -> of(null, null, null, null, 101, null, null, null, null).patch(preset(), 2), "spike over 100");
        assertThrows(IllegalArgumentException.class, () -> of(null, null, null, null, null, 0, null, null, null).patch(preset(), 2), "capitals on top of each other");
    }
}
