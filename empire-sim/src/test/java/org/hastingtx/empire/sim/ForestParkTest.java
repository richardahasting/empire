package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.steps.ProductionStep;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Issue #227. Richard, 2026-09-15: "a Forrest doubles as a Park as well as a source of building materials. It doesn't
 * produce much LCM, and it is not as populated as a park. But it might produce half as many happy strollers ... at a
 * significantly reduced cost" — $0.25 — and "the happiness is generated as leisure, not as labor. I don't think that
 * we have to reduce the LCM to add the happiness."
 */
class ForestParkTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord AT = Hex.stepRaw(TestWorlds.CENTER, 1, 1);
    private static final int HAPPINESS = 3;

    /** The production step alone on one sector of that type with 400 civilians, on forest ground at 100 fertility. */
    private static Ctx produce(String type) {
        World w = TestWorlds.disc(CFG, 2, Map.of("food", 400.0));
        w = w.withSector(w.sector(AT).withTerrain(Terrain.FOREST, 100, new Resources(100, 0, 0, 0, 0)));
        w = TestWorlds.own(w, CFG, AT, type, 100, 127, Map.of("civ", 400.0, "food", 2000.0), Map.of());
        Ctx ctx = new Ctx(w, CFG, COM, 3);
        new ProductionStep().run(ctx);
        return ctx;
    }

    @Test
    void aForestMakesItsLcmAndHalfAParksHappinessFromTheSamePeople() {
        Ctx forest = produce("forest"), park = produce("park");
        int i = forest.snap.index(AT);
        var type = CFG.sectorType("forest");
        double unit = forest.workAvailablePost(i) * forest.curve(type.levelEffect().curve(), 0);
        assertThat((double) forest.led().st(i, COM.index("lcm"))).as("the lcm is what a forest made before (whole units): happiness takes none of the work")
                .isBetween(Math.floor(unit * 0.002), Math.ceil(unit * 0.002)).isGreaterThan(0);
        assertThat(forest.led().level[0][HAPPINESS]).as("half a park's strollers per worker")
                .isCloseTo(unit * 0.0025, within(1e-6));
        assertThat(park.led().level[0][HAPPINESS]).isGreaterThan(0);
    }

    @Test
    void theStrollersCostAQuarterAndTheLcmIsFree() {
        Ctx forest = produce("forest"), park = produce("park");
        double strollers = forest.led().level[0][HAPPINESS];
        assertThat(-forest.led().cash[0]).as("$0.25 a point of happiness, nothing for the lcm").isCloseTo(strollers * 0.25, within(1e-6));
        assertThat(-park.led().cash[0]).as("a park still pays $1").isCloseTo(park.led().level[0][HAPPINESS], within(1e-6));
    }

    @Test
    void aForestHoldsHalfAParksPeople() {
        assertThat(CFG.sectorType("forest").maxPopulation()).isEqualTo(CFG.sectorType("park").maxPopulation() / 2);
    }
}
