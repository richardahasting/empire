package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.engine.view.Radar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #208. Richard, 2026-09-14: "radars don't seem to work. I built one, and it isn't showing me any
 * radius." A station designated radar reaches by its efficiency; everything in reach is on the map; enemy
 * ships in reach are detected.
 */
class RadarTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord STATION = Hex.stepRaw(CAP, 0, 2);      // on the rim of the disc
    private static final Coord FAR = Hex.stepRaw(CAP, 0, 10);         // eight hexes out to sea from it

    private static World world(double stationEfficiency) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        return TestWorlds.own(w, CFG, STATION, "radar", stationEfficiency, 127, Map.of("civ", 200.0, "food", 200.0), Map.of());
    }

    private static boolean sees(World w, Coord at) {
        return CountryView.of(w, CFG, 0).sectors().stream().anyMatch(s -> s.at().equals(at) && !s.remembered());
    }

    @Test
    void aStationRevealsTheMapWithinItsReach() {
        assertThat(sees(world(0), FAR)).as("a 0% station sees nothing extra").isFalse();
        World w = world(100);
        assertThat(Radar.range(CFG, w.sector(STATION), 0)).as("12 hexes at 100%, tech 0").isGreaterThanOrEqualTo(12);
        assertThat(sees(w, FAR)).as("eight hexes out is in reach").isTrue();
        assertThat(CountryView.of(w, CFG, 0).sectors().stream().filter(s -> s.at().equals(STATION)).findFirst().orElseThrow().radarRange())
                .as("the view carries the reach for the ring").isGreaterThanOrEqualTo(12);
        // and what it saw goes onto the chart
        World after = Update.run(w, CFG, 3).next();
        assertThat(after.seenBy(0)).anyMatch(m -> m.at().equals(FAR));
    }

    @Test
    void reachIsCappedWhateverTheTech() {
        World w = world(100);
        assertThat(Radar.range(CFG, w.sector(STATION), 900)).isEqualTo(CFG.detection().radar().cap());
    }

    @Test
    void enemyShipsInReachAreDetected() {
        World w = world(100);
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", new Coord(2, 2), 1000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        var c = CFG.units().ships().shipClass("cargo_ship");
        Coord near = Hex.stepRaw(CAP, 0, 5);                            // three hexes from the station
        w = w.withShips(List.of(new Ship(1, 1, "cargo_ship", "", near, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null, 0, c.crewOr0())), 2);
        boolean found = false;
        for (int i = 0; i < 8 && !found; i++) {
            w = Update.run(w, CFG, 40 + i).next();
            found = w.contactsOf(0).stream().anyMatch(k -> k.shipId() == 1);
        }
        assertThat(found).as("the station picked her up").isTrue();
    }
}
