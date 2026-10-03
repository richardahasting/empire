package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #71: fallout — KNOWN update/fallout.c (meltitems, spread_fallout, decay_fallout), detonate.c. */
class FalloutTest {
    private static final GameConfig CFG = TestWorlds.withFallout(TestWorlds.teaching());
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HOT = Hex.stepRaw(CAP, 0, 1);

    private static World world(int fallout) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = TestWorlds.own(w, CFG, HOT, "agribusiness", 100, 127, Map.of("civ", 600.0, "food", 2000.0, "iron", 600.0), Map.of());
        return w.withSector(w.sector(HOT).withFallout(fallout));
    }

    @Test
    void falloutMeltsPeopleAndGoodsSpreadsAndDecays() {
        World w = world(3000);
        UpdateResult r = Update.run(w, CFG, 3);   // the conservation check in apply passes: every melted thing is tallied
        World n = r.next();
        Sector hot = n.sector(HOT);
        assertThat(hot.fallout()).as("it decays").isLessThan(3000).isGreaterThan(0);
        // food melts fastest (melt 2), iron slowest (melt 100); nothing else here keeps them from changing so much
        double foodLeft = hot.stock().get(COM.food) / 2000.0, ironLeft = hot.stock().get(COM.index("iron")) / 600.0;
        assertThat(foodLeft).isLessThan(ironLeft);
        assertThat(ironLeft).isLessThan(1.0);
        int leaked = 0;
        for (Coord c : Hex.neighbours(n, HOT)) if (n.sector(c).fallout() > 0) leaked++;
        assertThat(leaked).as("it leaks into its neighbours").isGreaterThan(0);
    }

    @Test
    void noFalloutNoChangeAndASanctuaryIsSpared() {
        World clean = world(0);
        assertThat(Update.run(clean, CFG, 3).next().sector(HOT).fallout()).isZero();
        World w = world(3000);
        Coord haven = Hex.stepRaw(HOT, 0, 1);
        w = w.withSector(w.sector(haven).withSanctuary(true));
        assertThat(Update.run(w, CFG, 3).next().sector(haven).fallout()).isZero();
    }

    @Test
    void aSurfaceShipInItLosesCargoASubmarineDoesNot() {
        World w = world(3000);
        Coord sea = Hex.stepRaw(CAP, 0, 3);
        w = w.withSector(w.sector(sea).withFallout(3000));
        var dc = CFG.units().ships().shipClass("destroyer");
        var sc = CFG.units().ships().shipClass("submarine");
        w = w.withShip(new Ship(1, 0, "destroyer", "", sea, 100, Stocks.of(COM.fromMap(Map.of("food", 100.0))), null, null, 0, "", 100, null, null, dc.tankOr0(), dc.crewOr0()))
             .withShip(new Ship(2, 0, "submarine", "", sea, 100, Stocks.of(COM.fromMap(Map.of("food", 100.0))), null, null, 0, "", 100, null, null, sc.tankOr0(), sc.crewOr0()));
        World n = Update.run(w, CFG, 3).next();
        assertThat(n.ship(1).stock().get(COM.food)).isLessThan(100);
        assertThat(n.ship(2).stock().get(COM.food)).isEqualTo(100);
    }
}
