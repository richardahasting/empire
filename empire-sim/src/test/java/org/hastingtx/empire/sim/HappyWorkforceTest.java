package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Issue #230. Richard, 2026-09-15: "A happy workforce is a productive workforce ... it's hard to keep happiness at a
 * high level. Let's take off the limit altogether." The work bonus stopped at happiness 20.
 */
class HappyWorkforceTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Coord AT = Hex.stepRaw(TestWorlds.CENTER, 1, 1);

    private static double work(double happiness) {
        World w = TestWorlds.disc(CFG, 2, Map.of("food", 400.0));
        w = w.withCountry(w.country(0).withLevels(new Levels(0, 0, 0, happiness)));
        w = TestWorlds.own(w, CFG, AT, "warehouse", 100, 127, Map.of("civ", 1000.0, "food", 5000.0), Map.of());
        return new Ctx(w, CFG, Commodities.of(CFG), 1).workAvailable(w.sector(AT));
    }

    @Test
    void everyPointOfHappinessIsAPercentMoreWorkWithNoCeiling() {
        double base = work(0);
        assertThat(work(20) / base).isCloseTo(1.20, within(1e-9));
        assertThat(work(46) / base).as("Rick's 46 no longer works like 20").isCloseTo(1.46, within(1e-9));
        assertThat(work(100) / base).isCloseTo(2.00, within(1e-9));
    }

    @Test
    void aGameWhoseRulesStillCarryACeilingKeepsIt() {
        var capped = new EconomyCfg.WorkCfg.HappinessEffect(0, 0.01, 0.5, 1.2);
        assertThat(capped.eval(46)).isEqualTo(1.2);
        assertThat(new EconomyCfg.WorkCfg.HappinessEffect(0, 0.01, 0.5, null).eval(46)).isCloseTo(1.46, within(1e-9));
    }
}
