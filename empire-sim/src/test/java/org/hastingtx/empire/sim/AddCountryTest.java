package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.Terrain;
import org.hastingtx.empire.engine.model.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue #113: seating a new country in a world that is already being played. */
class AddCountryTest {

    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();
    private static final long SEED = 11L;

    private static World twoPlayers() { return new WorldGenerator(CFG).generate(List.of("A", "B"), SEED); }

    private static World add(World w, String name) { return new WorldGenerator(CFG).addCountry(w, name, SEED); }

    private static Sector capitalOf(World w, int id) { return w.sector(w.country(id).capital()); }

    @Test
    void theNewCountryIsSeatedWithACapitalAndASanctuary() {
        World before = twoPlayers();
        World after = add(before, "Carol");

        assertThat(after.countries()).hasSize(before.countries().size() + 1);
        Country c = after.countries().get(after.countries().size() - 1);
        assertThat(c.name()).isEqualTo("Carol");
        assertThat(c.id()).as("country id is its index in the list").isEqualTo(2);

        Sector cap = capitalOf(after, c.id());
        assertThat(cap.owner()).isEqualTo(c.id());
        assertThat(cap.designation()).isEqualTo("capital");
        assertThat(cap.terrain().isLand()).isTrue();

        assertThat(after.sectors().stream().filter(s -> s.owner() == c.id() && s.designation().equals("sanctuary")).count())
                .as("exactly one sanctuary").isEqualTo(1);
    }

    @Test
    void theCapitalIsStockedLikeAFoundingCountry() {
        World after = add(twoPlayers(), "Carol");
        Country carol = after.country(2), founder = after.country(0);
        assertThat(carol.cash()).isEqualTo(founder.cash());
        assertThat(carol.btu()).isEqualTo(founder.btu());
        assertThat(capitalOf(after, 2).stock().total()).isEqualTo(capitalOf(after, 0).stock().total());
    }

    @Test
    void theCapitalKeepsItsDistanceFromTheOthers() {
        World after = add(twoPlayers(), "Carol");
        Coord cap = after.country(2).capital();
        int min = CFG.world().terrain().minDistanceBetweenCapitals();
        for (int i = 0; i < 2; i++)
            assertThat(Hex.distance(after, cap, after.country(i).capital())).as("distance to country %d", i).isGreaterThanOrEqualTo(min);
    }

    @Test
    void nobodyElsesLandIsTakenOrDisturbed() {
        World before = twoPlayers();
        World after = add(before, "Carol");
        for (int i = 0; i < before.sectors().size(); i++) {
            Sector b = before.sectors().get(i), a = after.sectors().get(i);
            if (!b.owned()) continue;
            assertThat(a.owner()).as("sector %s changed hands", b.at()).isEqualTo(b.owner());
            assertThat(a.designation()).isEqualTo(b.designation());
            assertThat(a.stock()).isEqualTo(b.stock());
        }
    }

    @Test
    void addingIsDeterministic() {
        World before = twoPlayers();
        assertThat(add(before, "Carol").country(2).capital()).isEqualTo(add(before, "Carol").country(2).capital());
    }

    @Test
    void badNamesAreRefused() {
        World before = twoPlayers();
        assertThatThrownBy(() -> add(before, "A")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> add(before, "a")).as("names collide case-insensitively").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> add(before, "   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFullGameIsRefused() {
        int max = CFG.players().maxCountries();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < max; i++) names.add("C" + i);
        World full = new WorldGenerator(CFG).generate(names, SEED);
        assertThatThrownBy(() -> add(full, "OneTooMany"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("full");
    }

    /**
     * The world with no room left. The generator must refuse rather than raise land under someone —
     * and the refusal has to say which of the two reasons applies, because "lower the spacing" and
     * "you need a bigger world" are different actions for the deity.
     */
    @Test
    void aWorldWithNoVacantLandIsRefusedWithAReason() {
        World before = twoPlayers();
        List<Sector> sectors = new ArrayList<>(before.sectors());
        for (int i = 0; i < sectors.size(); i++) {
            Sector s = sectors.get(i);
            if (s.terrain().isLand() && !s.owned()) sectors.set(i, s.withOwner(0));
        }
        World full = before.withSectors(sectors);
        long seaBefore = full.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN).count();

        assertThatThrownBy(() -> add(full, "Carol"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nowhere to seat Carol")
                .hasMessageContaining("are owned")
                .hasMessageContaining("bigger world");

        assertThat(full.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN).count())
                .as("the map must not have been touched").isEqualTo(seaBefore);
    }

    /** When the blocker is spacing rather than ownership, the message should point at the spacing. */
    @Test
    void aCrowdedWorldSaysTheSpacingIsWhatIsInTheWay() {
        World before = twoPlayers();
        // nothing owned beyond the capitals, but every free hex is inside the exclusion radius
        GameConfig wide = new ConfigLoader().loadYaml(new ConfigLoader().toYaml(
                widened(new ConfigLoader().loadPreset("teaching").raw(), 999))).config();
        assertThatThrownBy(() -> new WorldGenerator(wide).addCountry(before, "Carol", SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within 999 sectors of an existing capital")
                .hasMessageContaining("Lowering the minimum distance");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> widened(java.util.Map<String, Object> raw, int spacing) {
        var out = new java.util.LinkedHashMap<>(raw);
        var world = new java.util.LinkedHashMap<>((java.util.Map<String, Object>) out.get("world"));
        var terrain = new java.util.LinkedHashMap<>((java.util.Map<String, Object>) world.get("terrain"));
        terrain.put("min_distance_between_capitals", spacing);
        world.put("terrain", terrain);
        out.put("world", world);
        return out;
    }

    /** A seated country must survive an update, not just exist in the returned world. */
    @Test
    void theNewCountryPlaysOn() {
        World after = add(twoPlayers(), "Carol");
        var result = org.hastingtx.empire.engine.update.Update.run(after, CFG, SEED);
        assertThat(result.next().countries()).hasSize(3);
        assertThat(result.next().country(2).name()).isEqualTo("Carol");
    }
}
