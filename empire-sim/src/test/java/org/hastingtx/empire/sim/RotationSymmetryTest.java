package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** rotate(update(W)) == update(rotate(W)) on an asymmetric world, for every k. */
class RotationSymmetryTest {

    static World lopsided(GameConfig cfg) {
        World w = TestWorlds.disc(cfg, 6, Map.of("civ", 500.0, "food", 4000.0, "lcm", 300.0, "iron", 200.0));
        Coord c = TestWorlds.CENTER;
        w = TestWorlds.own(w, cfg, Hex.stepRaw(c, 0, 1), "agribusiness", 60, 40, Map.of("civ", 120.0, "food", 30.0), Map.of("food", 150.0));
        w = TestWorlds.own(w, cfg, Hex.stepRaw(c, 0, 2), "mine", 30, 20, Map.of("civ", 90.0, "food", 5.0), Map.of("food", 80.0, "iron", 0.0));
        w = TestWorlds.own(w, cfg, Hex.stepRaw(Hex.stepRaw(c, 0, 2), 1, 1), "light_manufacturing", 50, 90, Map.of("civ", 200.0, "food", 400.0, "iron", 50.0), Map.of("iron", 100.0, "civ", 250.0));
        w = TestWorlds.own(w, cfg, Hex.stepRaw(c, 4, 3), "agribusiness", 10, 5, Map.of("civ", 30.0), Map.of("civ", 100.0, "food", 100.0));
        return w;
    }

    @Test
    void updateCommutesWithRotation() {
        GameConfig cfg = TestWorlds.teaching();
        World w = lopsided(cfg);
        World base = Update.run(w, cfg, 7).next();
        for (int k = 1; k < 6; k++) {
            World rotatedIn = TestWorlds.rotate(w, TestWorlds.CENTER, k);
            World out = Update.run(rotatedIn, cfg, 7).next();
            World expected = TestWorlds.rotate(base, TestWorlds.CENTER, k);
            for (Sector e : expected.sectors()) {
                Sector a = out.sector(e.at());
                assertThat(a.owner()).isEqualTo(e.owner());
                assertThat(a.designation()).isEqualTo(e.designation());
                assertThat(a.efficiency()).as("eff k=%d at %s", k, e.at()).isCloseTo(e.efficiency(), within(1e-6));
                assertThat(a.mobility()).as("mob k=%d at %s", k, e.at()).isCloseTo(e.mobility(), within(1e-6));
                for (int c = 0; c < e.stock().size(); c++) assertThat(a.stock().get(c)).as("stock %d k=%d at %s", c, k, e.at()).isCloseTo(e.stock().get(c), within(1e-6));
                assertThat(a.held().size()).isEqualTo(e.held().size());
            }
            assertThat(out.country(0).cash()).isCloseTo(expected.country(0).cash(), within(1e-6));
        }
    }
}
