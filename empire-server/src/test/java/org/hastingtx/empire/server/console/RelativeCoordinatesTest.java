package org.hastingtx.empire.server.console;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Ship;
import org.hastingtx.empire.engine.model.Stocks;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.sim.Sim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #271: a ship's note said "holding at 63,56" in absolute coordinates, the player copied it into
 * {@code sail}, which reads x,y from the capital, and was told "out of bounds: 126,113".
 */
class RelativeCoordinatesTest {
    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();
    private static final World W = new Sim(CFG).newWorld(List.of("P", "Q"), 7);

    @Test
    void aShipsNoteNamesPlacesFromTheCapital() {
        Coord cap = W.country(0).capital();
        Coord at = Hex.normalise(W, Hex.stepRaw(cap, 0, 3));
        Ship boat = new Ship(W.nextShipId(), 0, "fishing_boat", "", at, 100, Stocks.zero(Commodities.of(CFG).size()), null, null, 0,
                "out of fuel, holding at " + at.x() + "," + at.y() + "; distress call sent", 0, null, null, 0, 5);
        CountryView v = CountryView.of(W.withShip(boat), CFG, 0);
        CountryView.ShipView seen = v.ships().stream().filter(s -> s.id() == boat.id()).findFirst().orElseThrow();
        assertThat(seen.note()).isEqualTo("out of fuel, holding at " + seen.relative().x() + "," + seen.relative().y() + "; distress call sent");
    }

    @Test
    void absoluteCoordinatesOffAnEdgeSayHowCoordinatesWork() {
        CountryView v = CountryView.of(W, CFG, 0);
        // a world that does not wrap, as game 82's does not, with nothing in view so every coordinate is extrapolated
        CountryView flat = new CountryView(v.countryId(), v.name(), v.updateNumber(), v.capital(), false, false, v.width(), v.height(), v.cash(), v.btu(), v.levels(), v.handicap(),
                v.inSanctuary(), v.bankrupt(), v.commodityIds(), List.of(), v.otherCountryNames(), v.atWarWith(), List.of(), List.of(), List.of(), List.of(), v.units(), v.planes());
        Coord cap = v.capital();
        int ax = v.width() - 1, ay = v.height() - 1;   // a map position on the far corner: from the capital, off the edge
        assertThat(cap.x() + ax >= v.width() || cap.y() + ay >= v.height()).as("capital is not at 0,0").isTrue();
        assertThatThrownBy(() -> Console.abs(flat, ax + "," + ay))
                .hasMessageContaining("off the map: coordinates are counted from your capital, which is 0,0")
                .hasMessageContaining("it is " + (ax - cap.x()) + "," + (ay - cap.y()) + " from your capital");
        assertThat(Console.abs(flat, "1,0")).isEqualTo(new Coord(cap.x() + 1, cap.y()));
    }
}
