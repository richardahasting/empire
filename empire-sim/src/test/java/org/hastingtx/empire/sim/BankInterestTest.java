package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Issue #219. Richard, 2026-09-14: "we're supposed to be earning interest on the bars of gold that we have
 * in banks." The original (update/prepare.c bank_income) pays bars × ETUs × 0.25 × efficiency / 100; we paid
 * a hundredth of that, whatever the bank's efficiency.
 */
class BankInterestTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Coord AT = Hex.stepRaw(TestWorlds.CENTER, 1, 1);

    /** The cash one update brings with {@code bars} in a sector of that type and efficiency, less the same world without them. */
    private static double interest(String type, double efficiency, double bars) {
        World base = TestWorlds.own(TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0)), CFG, AT, type, efficiency, 127, Map.of("civ", 100.0, "food", 500.0), Map.of());
        World with = TestWorlds.own(TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0)), CFG, AT, type, efficiency, 127, Map.of("civ", 100.0, "food", 500.0, "bar", bars), Map.of());
        double a = Update.run(base, CFG, 9).next().country(0).cash() - base.country(0).cash();
        double b = Update.run(with, CFG, 9).next().country(0).cash() - with.country(0).cash();
        return b - a;
    }

    @Test
    void aBarInAFullBankEarnsAQuarterAnEtu() {
        assertThat(CFG.options().interest()).isTrue();
        assertThat(interest("bank", 100, 1000)).isCloseTo(1000 * 0.25 * CFG.etus(), within(1.0));
    }

    @Test
    void aHalfBuiltBankEarnsHalf() {
        assertThat(interest("bank", 50, 1000)).isCloseTo(1000 * 0.25 * CFG.etus() * 0.5, within(1.0));
    }

    @Test
    void barsInAWarehouseEarnNothing() {
        assertThat(interest("warehouse", 100, 1000)).isCloseTo(0, within(1.0));
    }
}
