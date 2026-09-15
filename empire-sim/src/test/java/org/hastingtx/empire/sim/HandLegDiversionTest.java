package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #213 (Wolfy's playtest of game 82, 2026-09-14): fishing boat #56, sailed by hand toward 12,-6, ran low on fuel,
 * turned for harbour 1,-1 and arrived, and stayed "sailing by hand" at the pier with her fishing paused for good.
 */
class HandLegDiversionTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOR = Hex.stepRaw(CAP, 0, 2);
    private static final Coord AT_SEA = Hex.stepRaw(CAP, 0, 4);          // two hexes out: home in one update, as #56 was
    private static final Coord FAR = Hex.stepRaw(CAP, 0, 10);            // where she was sent

    @Test
    void aHandLegCutShortForFuelEndsInPortAndTheOrderResumes() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        w = TestWorlds.own(w, CFG, HARBOR, "harbor", 100, 127, Map.of("civ", 500.0, "food", 300.0, "pet", 2000.0), Map.of());
        var cls = CFG.units().ships().shipClass("fishing_boat");
        double reserve = CFG.units().ships().missionsOrDefault().reserve();
        double fuel = 2 * cls.fuelPerHexOr0() * reserve + 0.5 * cls.fuelPerHexOr0();      // home, and not a hex further out
        Ship s = new Ship(1, 0, "fishing_boat", "", AT_SEA, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null, fuel, cls.crewOr0())
                .withMission(Ship.FISH, HARBOR).withDest(FAR).withHandLeg(true).withMobility(10);
        w = w.withShips(List.of(s), 2);

        boolean docked = false;
        String arrival = "";
        for (int u = 0; u < 1 && !docked; u++) {
            w = Update.run(w, CFG, 30 + u).next();
            Ship b = w.ship(1);
            if (b.at().equals(HARBOR)) { docked = true; arrival = b.note(); }
        }
        assertThat(docked).as("she turned for the harbour and got there the same update").isTrue();
        Ship b = w.ship(1);
        assertThat(b.handLeg()).as("no longer sailing by hand at the pier: " + arrival).isFalse();
        assertThat(b.fishing()).as("still a fishing boat").isTrue();
        assertThat(arrival).contains("her fishing resumes");
    }
}
