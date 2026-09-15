package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.steps.MoneyStep;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Issues #219 and #221. Richard, 2026-09-14: "we're supposed to be earning interest on the bars of gold that we have
 * in banks." The original (update/prepare.c bank_income) pays bars × ETUs × 0.25 × efficiency / 100; we paid
 * a hundredth of that, whatever the bank's efficiency.
 */
class IncomeTest {
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

    /** Issue #221: tax is scaled the same way (prepare.c tax); military pay is not. The money step alone, so build-up spending does not muddy it. */
    @Test
    void civiliansPayTaxByTheirSectorsEfficiency() {
        var m = CFG.economy().money();
        double full = moneyStep(100, 400, 0), part = moneyStep(40, 400, 0);
        assertThat(full).isCloseTo(400 * m.taxPerCivPerEtu() * CFG.etus(), within(0.01));
        assertThat(part).isCloseTo(full * 0.4, within(0.01));
        assertThat(moneyStep(40, 0, 100)).as("soldiers are paid in full whatever the efficiency").isCloseTo(moneyStep(100, 0, 100), within(0.01));
    }

    /** Country 0's cash from the money step alone, over a world where only one warehouse holds people. */
    private static double moneyStep(double efficiency, double civ, double mil) {
        World w = TestWorlds.disc(CFG, 2, Map.of("food", 400.0));
        w = TestWorlds.own(w, CFG, AT, "warehouse", efficiency, 127, Map.of("civ", civ, "mil", mil), Map.of());
        Ctx ctx = new Ctx(w, CFG, Commodities.of(CFG), 9);
        new MoneyStep().run(ctx);
        return ctx.led().cash[0];
    }

    @Test
    void barsInAWarehouseEarnNothing() {
        assertThat(interest("warehouse", 100, 1000)).isCloseTo(0, within(1.0));
    }
}
