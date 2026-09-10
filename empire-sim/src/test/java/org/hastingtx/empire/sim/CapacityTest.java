package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sector holds ten thousand and a warehouse holds ten times that (issue #91).
 *
 * <p>These are the tests that would have caught leaving quantities as {@code short}. The failure would
 * not have been subtle for long — the apply step computes the new stock as an {@code int}, so a clamp
 * on the way into storage shows up as the conservation check finding the books short and throwing —
 * but nothing in the suite reached 32,767 of anything, so nothing would have noticed at all.
 */
class CapacityTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Coord WAREHOUSE = new Coord(11, 12);

    @Test
    void aPlainSectorHoldsTenThousand() {
        assertThat(CFG.economy().defaultCapacity()).isEqualTo(10_000);
    }

    @Test
    void aWarehouseHoldsAHundredThousandAndConservationStillBalances() {
        Commodities com = Commodities.of(CFG);
        World w = TestWorlds.disc(CFG, 3, Map.of("civ", 500.0, "food", 900.0));
        w = TestWorlds.own(w, CFG, WAREHOUSE, "warehouse", 100, 0, Map.of("iron", 100_000.0), Map.of());

        // it went in whole, not clamped at a short's 32,767
        assertThat(w.sector(WAREHOUSE).stock().get(com.index("iron"))).isEqualTo(100_000.0);

        // and the update accepts it: apply throws if conservation is out by a single unit
        UpdateResult r = Update.run(w, CFG, 4242L);
        assertThat(r.next().sector(WAREHOUSE).stock().get(com.index("iron")))
                .describedAs("a warehouse's iron is not spoiled away as over capacity")
                .isEqualTo(100_000.0);
    }

    @Test
    void aboveTheWarehouseCeilingStillSpoils() {
        Commodities com = Commodities.of(CFG);
        World w = TestWorlds.disc(CFG, 3, Map.of("civ", 500.0, "food", 900.0));
        w = TestWorlds.own(w, CFG, WAREHOUSE, "warehouse", 100, 0, Map.of("iron", 150_000.0), Map.of());

        UpdateResult r = Update.run(w, CFG, 4242L);
        assertThat(r.next().sector(WAREHOUSE).stock().get(com.index("iron")))
                .describedAs("the cap is still a cap — the excess is destroyed and tallied, not kept")
                .isEqualTo(100_000.0);
    }

    @Test
    void aThresholdCanBeSetAboveAShort() {
        World w = TestWorlds.disc(CFG, 3, Map.of("civ", 500.0, "food", 900.0));
        Sector s = w.sector(TestWorlds.CENTER).withThreshold(0, 90_000);
        assertThat(s.threshold(0)).isEqualTo(90_000.0);
        assertThat(s.deliver().with(0, 1, 90_000).threshold(0)).isEqualTo(90_000.0);
    }
}
