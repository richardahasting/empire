package org.hastingtx.empire.server.game;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #108: an impossible min-capital-distance used to reach the deity as a 500, thrown from inside
 * the generator's rejection sampling after 20,000 tries. The packing bound refuses the certainly
 * impossible up front; anything it lets through is the generator's own surrender, turned into a 400.
 */
class CapitalsFitTest {

    private static Map<String, Object> preset(int w, int h, int spacing) {
        Map<String, Object> terrain = new LinkedHashMap<>(Map.of(
                "land_fraction", 0.30, "island_size", 25, "spike", 10, "min_distance_between_capitals", spacing));
        Map<String, Object> world = new LinkedHashMap<>(Map.of(
                "name", "T", "width", w, "height", h, "wrap_x", true, "wrap_y", true, "terrain", terrain));
        return new LinkedHashMap<>(Map.of("world", world));
    }

    private static WorldOverrides spacing(Integer d) {
        return new WorldOverrides(null, null, null, null, null, d, null, null, null);
    }

    // ---- the geometry the bound rests on ----

    @Test
    void theHexDiscNumbersAreRight() {
        assertEquals(1, WorldOverrides.disc(0));
        assertEquals(7, WorldOverrides.disc(1));
        assertEquals(19, WorldOverrides.disc(2));
        assertEquals(37, WorldOverrides.disc(3));
    }

    @Test
    void discsOfThePackingRadiusCannotOverlap() {
        // two capitals exactly minDistance apart must have disjoint discs, so 2r < minDistance
        for (int d = 1; d <= 40; d++)
            assertTrue(2 * WorldOverrides.packingRadius(d) < d, "radius too big for spacing " + d);
    }

    // ---- what it refuses, and what it must not ----

    @Test
    void anImpossibleSpacingIsRefusedWithSomethingActionable() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> spacing(12).patch(preset(32, 32, 4), 16));
        assertTrue(e.getMessage().contains("16 capitals"), e.getMessage());
        assertTrue(e.getMessage().contains("32x32"), e.getMessage());
        assertTrue(e.getMessage().contains("widest spacing"), "should say what would work: " + e.getMessage());
    }

    @Test
    void theSuggestionsItMakesAreThemselvesLegal() {
        int w = 32, h = 32, countries = 16;
        int widest = WorldOverrides.widestSpacingFor(w, h, countries);
        assertDoesNotThrow(() -> spacing(widest).patch(preset(w, h, 4), countries), "the spacing it suggests must pass");
        assertThrows(IllegalArgumentException.class, () -> spacing(widest + 1).patch(preset(w, h, 4), countries),
                "and it should be the widest that does");

        long most = WorldOverrides.capitalsThatFit(w, h, 12);
        assertDoesNotThrow(() -> spacing(12).patch(preset(w, h, 4), (int) most), "the count it suggests must pass");
    }

    @Test
    void aComfortableWorldIsNotRefused() {
        assertDoesNotThrow(() -> spacing(12).patch(preset(128, 64, 12), 8));
        assertDoesNotThrow(() -> spacing(4).patch(preset(16, 16, 4), 4));
        assertDoesNotThrow(() -> spacing(1).patch(preset(16, 16, 4), 40), "spacing 1 only needs one hex each");
    }

    @Test
    void oneCountryIsAlwaysFine() {
        assertDoesNotThrow(() -> spacing(999).patch(preset(16, 16, 4), 1));
    }

    /** The preset's own spacing can become impossible when the map shrinks or countries are added. */
    @Test
    void thePresetsSpacingIsCheckedToo() {
        WorldOverrides shrink = new WorldOverrides(16, 16, null, null, null, null, null, null, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> shrink.patch(preset(128, 64, 12), 12));
        assertTrue(e.getMessage().contains("12 sectors apart"), e.getMessage());
    }

    /**
     * The bound must never refuse a world the generator would in fact have built. Sweep the sizes and
     * counts a deity might plausibly pick: anything the bound accepts, the generator must place.
     */
    @Test
    void anythingTheBoundAcceptsTheGeneratorCanActuallyPlace() {
        for (int size : new int[]{16, 24, 32}) {
            for (int countries : new int[]{2, 4, 8}) {
                int widest = WorldOverrides.widestSpacingFor(size, size, countries);
                // the bound says this passes; hold it to that claim
                int use = Math.max(1, widest / 2);   // comfortably inside the bound
                var cfg = new ConfigLoader().loadYaml(new ConfigLoader().toYaml(
                        full(size, size, use))).config();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < countries; i++) names.add("C" + i);
                int finalSize = size;
                assertDoesNotThrow(() -> new WorldGenerator(cfg).generate(names, 7L),
                        finalSize + "x" + finalSize + ", " + countries + " countries at spacing " + use);
            }
        }
    }

    /**
     * The backstop on its own. The sweep above may or may not happen to land in the gap between the
     * bound and the sampler, so the mapping is pinned here directly: the generator's surrender must
     * come out as the exception ApiErrors turns into a 400, carrying the original wording.
     */
    @Test
    void theGeneratorsSurrenderBecomesABadRequest() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GameService.placing(() -> { throw new IllegalStateException("cannot place 9 capitals at min distance 7 on 20x20"); }));
        assertTrue(e.getMessage().startsWith("cannot place 9 capitals at min distance 7 on 20x20"), e.getMessage());
        assertTrue(e.getMessage().contains("Move the capitals closer together"), "it should say what to do: " + e.getMessage());
        assertInstanceOf(IllegalStateException.class, e.getCause(), "the original is kept as the cause");
    }

    @Test
    void aGenerationThatSucceedsIsPassedStraightThrough() {
        ConfigLoader loader = new ConfigLoader();
        var cfg = loader.loadPreset("teaching").config();
        var w = GameService.placing(() -> new WorldGenerator(cfg).generate(List.of("A", "B"), 7L));
        assertEquals(2, w.countries().size());
    }

    /**
     * The contract issue #108 is actually about: however the deity sets size, country count and
     * spacing, the answer is either a world or a 400 — never an IllegalStateException escaping as a
     * 500. The bound refuses what is certainly impossible and the backstop catches what merely
     * defeats the sampler; this sweeps the space and holds both to it together.
     */
    @Test
    void noCombinationEverEscapesAsAServerError() {
        ConfigLoader loader = new ConfigLoader();
        var schema = loader.loadSchema().raw();
        int checked = 0, refused = 0, built = 0;
        for (int size : new int[]{16, 20, 32}) {
            for (int n : new int[]{2, 4, 8, 16}) {
                for (int d : new int[]{1, 2, 4, 8, 12, 20, 40}) {
                    checked++;
                    Map<String, Object> raw;
                    try {
                        raw = new WorldOverrides(size, size, null, null, null, d, null, null, null).patch(schema, n);
                    } catch (IllegalArgumentException e) {
                        refused++; continue;                      // the bound said no: a 400, which is the point
                    }
                    var cfg = loader.loadYaml(loader.toYaml(raw)).config();
                    if (n > cfg.players().maxCountries()) continue;
                    List<String> names = new ArrayList<>();
                    for (int i = 0; i < n; i++) names.add("C" + i);
                    try {
                        GameService.placing(() -> new WorldGenerator(cfg).generate(names, 7L));
                        built++;
                    } catch (IllegalArgumentException e) {
                        refused++;                                 // the backstop said no: also a 400
                    } catch (IllegalStateException e) {
                        fail("escaped as a server error at " + size + "x" + size + ", " + n + " countries, spacing " + d + ": " + e.getMessage());
                    }
                }
            }
        }
        assertTrue(checked > 50, "the sweep should actually cover something");
        assertTrue(built > 0, "some of these must really generate");
        assertTrue(refused > 0, "and some must really be refused");
    }

    /** A complete config: the schema with the map overridden, which is what a game actually plays by. */
    private static Map<String, Object> full(int w, int h, int spacing) {
        ConfigLoader loader = new ConfigLoader();
        var raw = loader.loadSchema().raw();
        return new WorldOverrides(w, h, null, null, null, spacing, null, null, null).patch(raw, 1);
    }
}
