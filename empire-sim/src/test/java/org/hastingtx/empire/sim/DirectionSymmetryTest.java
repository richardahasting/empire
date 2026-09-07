package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Flow;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The spec's proof: the same supply chain in all six hex directions yields the same result.
 * The original's scan-order artifact would make the SE chain win and the NW chain crawl.
 */
class DirectionSymmetryTest {
    static final int LEN = 4;

    /** Six four-sector chains radiating from the capital; each sector pulls food and civs from the centre. */
    static World sixChains(GameConfig cfg) {
        World w = TestWorlds.disc(cfg, LEN + 1, Map.of("civ", 600.0, "food", 9000.0, "lcm", 500.0));
        for (int d = 0; d < 6; d++) for (int k = 1; k <= LEN; k++) {
            Coord at = Hex.stepRaw(TestWorlds.CENTER, d, k);
            w = TestWorlds.own(w, cfg, at, "agribusiness", 40, 60, Map.of("civ", 50.0, "food", 10.0), Map.of("civ", 120.0, "food", 200.0));
        }
        return w;
    }

    @Test
    void allSixDirectionsDeliverIdentically() {
        GameConfig cfg = TestWorlds.teaching();
        World w = sixChains(cfg);
        UpdateResult r = Update.run(w, cfg, 42);
        World n = r.next();
        int civ = 0, food = 3; // teaching preset commodity order: civ first, food fourth
        for (int k = 1; k <= LEN; k++) {
            Sector ref = n.sector(Hex.stepRaw(TestWorlds.CENTER, 0, k));
            for (int d = 1; d < 6; d++) {
                Sector s = n.sector(Hex.stepRaw(TestWorlds.CENTER, d, k));
                assertThat(s.stock().get(civ)).as("civ at distance %d dir %d", k, d).isCloseTo(ref.stock().get(civ), within(1e-6));
                assertThat(s.stock().get(food)).as("food at distance %d dir %d", k, d).isCloseTo(ref.stock().get(food), within(1e-6));
                assertThat(s.mobility()).as("mobility at distance %d dir %d", k, d).isCloseTo(ref.mobility(), within(1e-6));
                assertThat(s.held().size()).isEqualTo(ref.held().size());
            }
        }
        // and something actually moved: the chain's far end got food this update
        Sector far = n.sector(Hex.stepRaw(TestWorlds.CENTER, 3, 1));
        assertThat(far.stock().get(food)).isGreaterThan(10.0);
        assertThat(r.flows()).anyMatch(Flow::completed);
    }

    @Test
    void reachIsHonouredAndCargoHoldsInPlace() {
        GameConfig cfg = TestWorlds.teaching();
        // teaching: max_reach base 3 at tech 0 -> the 4th sector out is beyond reach in one update
        World w = sixChains(cfg);
        UpdateResult r = Update.run(w, cfg, 42);
        int food = 3;
        Sector fourth = r.next().sector(Hex.stepRaw(TestWorlds.CENTER, 0, 4));
        assertThat(fourth.stock().get(food)).as("beyond reach: not delivered this update").isLessThan(50);
        Sector third = r.next().sector(Hex.stepRaw(TestWorlds.CENTER, 0, 3));
        assertThat(third.held()).as("the parcel bound for sector 4 holds in sector 3").isNotEmpty();
        assertThat(r.flows()).anyMatch(f -> !f.completed() && f.holdReason() != null && f.holdReason().startsWith("reach"));
        // second update: the held parcel resumes and arrives
        UpdateResult r2 = Update.run(r.next(), cfg, 43);
        Sector fourthAfter = r2.next().sector(Hex.stepRaw(TestWorlds.CENTER, 0, 4));
        assertThat(fourthAfter.stock().get(food)).isGreaterThan(fourth.stock().get(food));
        assertThat(r2.next().sector(Hex.stepRaw(TestWorlds.CENTER, 0, 3)).held())
                .as("the update-1 parcel has moved on; only newer ones may hold here")
                .noneMatch(p -> p.issuedUpdate() == 0);
    }
}
