package org.hastingtx.empire.server.console;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #162: the listing says which standing missions a class can be given, by what the engine would accept. */
class FleetListingTest {
    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();

    @Test
    void missionsFollowWhatTheEngineWouldAccept() {
        assertThat(Console.missionsOf(CFG, "fishing_boat")).isEqualTo("fish");
        assertThat(Console.missionsOf(CFG, "mining_ship")).isEqualTo("mine");
        assertThat(Console.missionsOf(CFG, "cargo_ship")).isEqualTo("lane, supply");
        assertThat(Console.missionsOf(CFG, "tanker")).isEqualTo("lane, supply");
        assertThat(Console.missionsOf(CFG, "destroyer")).isEqualTo("fire, patrol, search, escort, blockade, interdict");
        assertThat(Console.missionsOf(CFG, "luxury_craft")).isEqualTo("lane, supply");
        assertThat(Console.missionsOf(CFG, "no_such_class")).isEqualTo("sail");
    }
}
