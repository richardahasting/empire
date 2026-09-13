package org.hastingtx.empire.server.console;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #163: the public rules carry what the inspector needs to say what a sector will make. */
class RulesCarryProductionMathsTest {
    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();

    @Test
    void workCurvesAndGatesAreInTheRulebook() {
        assertThat(CFG.economy().work().perCiv()).isGreaterThan(0);
        assertThat(CFG.economy().curves()).containsKey("tech_easy");
        var mine = CFG.sectorType("mine");
        assertThat(mine.resourceGate()).isEqualTo("minerals");
        assertThat(mine.levelEffect()).isNotNull();
        // the curve the inspector re-evaluates client-side matches the engine's at a known point
        assertThat(CFG.economy().curves().get("tech_easy").eval(0)).isCloseTo(0.5, org.assertj.core.api.Assertions.within(1e-9));
    }
}
