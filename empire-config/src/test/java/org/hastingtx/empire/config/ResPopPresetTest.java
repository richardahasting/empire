package org.hastingtx.empire.config;

import org.hastingtx.empire.engine.config.GameConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Issue #153: classic and blitz scale the population ceiling by research; teaching and sandbox stay flat. */
class ResPopPresetTest {
    @Test
    void classicAndBlitzUseResPopAndTheCurveIsTheOriginals() {
        for (String preset : new String[] {"classic", "blitz"}) {
            GameConfig cfg = new ConfigLoader().loadPreset(preset).config();
            var curve = cfg.economy().population().maxPopResearchCurve();
            assertThat(curve.type()).as(preset).isEqualTo("res_pop");
            assertThat(curve.eval(0)).as(preset + " at research 0").isCloseTo(0.55, within(1e-9));
            assertThat(curve.eval(40)).isCloseTo(0.4 + 0.6 * 210.0 / 320.0, within(1e-9));
            assertThat(curve.eval(150)).as("reaches the flat cap at research 150 and never exceeds it").isCloseTo(1.0, within(1e-9));
            assertThat(curve.eval(1000)).isEqualTo(1.0);
        }
        for (String preset : new String[] {"teaching", "sandbox"})
            assertThat(new ConfigLoader().loadPreset(preset).config().economy().population().maxPopResearchCurve().eval(0)).as(preset).isEqualTo(1.0);
    }
}
