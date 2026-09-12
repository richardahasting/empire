package org.hastingtx.empire.config;

import org.hastingtx.empire.engine.config.GameConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ConfigLoaderTest {
    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void schemaBindsCompletely() {
        GameConfig c = loader.loadSchema().config();
        assertThat(c.world().width()).isEqualTo(128);
        assertThat(c.economy().sectorTypes()).hasSizeGreaterThan(25);
        assertThat(c.economy().mobility().efficiencyDiscount().at100()).isEqualTo(0.5);   // at_100 -> at100 (KNOWN: mob1 = mob0 / 2)
        assertThat(c.sectorType("hospital").plagueMitigation().mortalityMultiplierAt100()).isEqualTo(0.4);
        assertThat(c.infrastructure().rail().capacityPerUpdateAt100()).isEqualTo(5000);
        assertThat(c.distribution().maxReachSectors().roadBonusAt100()).isEqualTo(2);
        assertThat(c.economy().efficiency().redesignate().keepFractionByPair()).containsKey("sanctuary->*");
    }

    @Test
    void presetsExtendSchema() {
        for (String p : List.of("classic", "blitz", "teaching", "sandbox")) {
            GameConfig c = loader.loadPreset(p).config();
            assertThat(c.world().name()).isNotBlank();
            assertThat(c.commodities()).hasSize(14);   // inherited
        }
        GameConfig blitz = loader.loadPreset("blitz").config();
        assertThat(blitz.world().width()).isEqualTo(32);
        assertThat(blitz.players().startingCommodities().capital().get("civ")).isEqualTo(900.0);
        assertThat(blitz.players().startingCommodities().capital().get("iron")).isEqualTo(0.0); // deep-merged map keeps base keys
        assertThat(loader.loadPreset("teaching").config().units().enabled()).isFalse();
    }

    @Test
    void hashIsStableAndDistinguishesPresets() {
        assertThat(loader.loadPreset("teaching").hash()).isEqualTo(loader.loadPreset("teaching").hash());
        assertThat(loader.loadPreset("teaching").hash()).isNotEqualTo(loader.loadPreset("blitz").hash());
    }

    @Test
    void unknownKeyIsRejected() throws Exception {
        Path dir = Files.createTempDirectory("empire-cfg");
        Path f = dir.resolve("bad.yaml");
        Files.writeString(f, "extends: " + schemaPath() + "\nworld: { widht: 12 }\n");
        assertThatThrownBy(() -> loader.load(f)).isInstanceOf(ConfigException.class).hasMessageContaining("widht");
    }

    @Test
    void crossReferencesAreValidated() throws Exception {
        Path dir = Files.createTempDirectory("empire-cfg");
        Path f = dir.resolve("bad.yaml");
        Files.writeString(f, "extends: " + schemaPath() + "\nworld: { terrain: { land_mix: { plains: 0.5, tundra: 0.5 } } }\n");
        assertThatThrownBy(() -> loader.load(f)).isInstanceOf(ConfigException.class).hasMessageContaining("unknown terrain tundra");
    }

    /**
     * Issue #106: continent_count was inert config, and removing it from WorldCfg is not free —
     * the binder rejects unknown keys, so every game snapshot stored before the removal has to be
     * migrated (V17) or GameService.loadAll will quietly drop those games. This pins the reason:
     * an old snapshot really does fail to bind now.
     */
    @Test
    void aSnapshotStillCarryingContinentCountNoLongerBinds() throws Exception {
        Path dir = Files.createTempDirectory("empire-cfg");
        Path f = dir.resolve("old-snapshot.yaml");
        Files.writeString(f, "extends: " + schemaPath() + "\nworld: { terrain: { continent_count: 0 } }\n");
        assertThatThrownBy(() -> loader.load(f)).isInstanceOf(ConfigException.class).hasMessageContaining("continent_count");
    }

    /**
     * A game keeps its own copy of the rules for life, so a snapshot written before a setting existed
     * has no value for it. The binder is strict by design, and a primitive cannot hold "absent" — so
     * a new primitive field stops every older game loading, and GameService.loadAll catches that per
     * game and logs it, which makes the failure a world quietly missing rather than a crash.
     *
     * <p>That happened in production on 2026-09-11: three primitives added to units.ships took the
     * live game off the server until they were boxed. This is the guard. Any setting added after a
     * game could have been created must survive being absent.
     */
    @Test
    void aSnapshotWrittenBeforeTodaysSettingsStillBinds() throws Exception {
        Path dir = Files.createTempDirectory("empire-cfg");
        Path f = dir.resolve("old-game.yaml");
        // a units.ships block as it was before seabed mining, immediate sail and sea wear existed
        Files.writeString(f, "extends: " + schemaPath() + "\n" + """
                units:
                  ships:
                    mining_ore_per_etu_per_mineral_point: null
                    sea_wear_per_update: null
                    refit_below: null
                    refit_resume_at: null
                    immediate_sail: null
                    mobility_cap_updates: null
                    immediate_sail_mobility_multiplier: null
                """);
        GameConfig c = loader.load(f);
        var ships = c.units().ships();
        assertThat(ships.seaWear()).as("an older game simply has no sea wear").isZero();
        assertThat(ships.immediate()).as("and keeps the update-time sailing it had").isFalse();
        assertThat(ships.oreRate()).isZero();
        assertThat(ships.rushCost()).isEqualTo(1.0);
        assertThat(ships.refitUpTo()).isEqualTo(100.0);
    }

    private static String schemaPath() {
        return Path.of("..", "config", "schema.yaml").toAbsolutePath().normalize().toString();
    }
}
