package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.view.CountryView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fog closes behind a ship; the chart does not blank (issue #64).
 *
 * <p>The acceptance test here is the downstream one, not the round trip: it is not enough that a
 * SeenSector was written somewhere. A player has to still be able to see the hex on their chart after
 * the ship has gone, marked as a memory rather than as fact, with the right age on it.
 */
class MapMemoryTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;

    private static World world() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        return w.withCountry(w.country(0).withCash(100000));
    }

    private static World withShipAt(World w, Coord at) {
        Ship s = new Ship(w.nextShipId(), 0, "cargo_ship", "", at, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null);
        return w.withShips(List.of(s), s.id() + 1);
    }

    @Test
    void aCoastlineSailedPastStaysOnTheChart() {
        Coord farSea = Hex.stepRaw(CAP, 0, 8);          // well beyond the disc, only ever seen from the ship
        World w = withShipAt(world(), Hex.stepRaw(CAP, 0, 6));

        // nothing remembered yet, but the ship can see it
        assertThat(CountryView.of(w, CFG, 0).sectors()).noneMatch(CountryView.SectorView::remembered);
        assertThat(CountryView.of(w, CFG, 0).sectors()).anyMatch(s -> s.at().equals(farSea));

        // an update commits what is visible to the chart
        World after = Update.run(w, CFG, 11).next();
        assertThat(after.seenBy(0)).extracting(SeenSector::at).contains(farSea);

        // now sail the ship home and let the fog close
        World gone = after.withShips(List.of(), after.nextShipId());
        CountryView v = CountryView.of(gone, CFG, 0);

        CountryView.SectorView remembered = v.sectors().stream().filter(s -> s.at().equals(farSea)).findFirst().orElse(null);
        assertThat(remembered).describedAs("the hex is still on the chart after the ship left").isNotNull();
        assertThat(remembered.remembered()).isTrue();
        assertThat(remembered.full()).isFalse();
        assertThat(remembered.terrain()).isEqualTo("ocean");
        assertThat(remembered.age()).describedAs("seen this update, so no age yet").isZero();
    }

    @Test
    void aMemoryAgesButIsNeverForgotten() {
        Coord farSea = Hex.stepRaw(CAP, 0, 8);
        World w = Update.run(withShipAt(world(), Hex.stepRaw(CAP, 0, 6)), CFG, 11).next();
        w = w.withShips(List.of(), w.nextShipId());

        for (int i = 0; i < 3; i++) w = Update.run(w, CFG, 12 + i).next();

        CountryView.SectorView s = CountryView.of(w, CFG, 0).sectors().stream()
                .filter(v -> v.at().equals(farSea)).findFirst().orElseThrow();
        assertThat(s.remembered()).isTrue();
        assertThat(s.age()).describedAs("three updates since anyone looked").isEqualTo(3);
    }

    @Test
    void aMemoryHoldsWhatWasThereNotWhatIsThere() {
        Coord farSea = Hex.stepRaw(CAP, 0, 8);          // out of sight: nothing of ours is near it
        World w = Update.run(world(), CFG, 11).next();
        assertThat(w.sector(farSea).terrain()).isEqualTo(Terrain.OCEAN);

        // remember it, wrongly, as land — if the view reads the world instead of the chart, this shows up
        World w2 = w.withSeen(List.of(new SeenSector(0, farSea, Terrain.PLAINS, Sector.NOBODY, "wilderness", w.updateNumber())));

        CountryView.SectorView s = CountryView.of(w2, CFG, 0).sectors().stream()
                .filter(v -> v.at().equals(farSea)).findFirst().orElseThrow();
        assertThat(s.remembered()).isTrue();
        assertThat(s.terrain()).describedAs("the chart says what was seen, not what is there now").isEqualTo("plains");

        // and a memory never carries the sector's contents
        assertThat(s.stock()).isEmpty();
        assertThat(s.thresholds()).isEmpty();
        assertThat(s.held()).isEmpty();
        assertThat(s.mobility()).isZero();
        assertThat(s.roadLevel()).isZero();
    }

    @Test
    void whatYouCanSeeIsNotMarkedRemembered() {
        World w = Update.run(world(), CFG, 11).next();
        CountryView v = CountryView.of(w, CFG, 0);
        assertThat(v.sectors().stream().filter(s -> s.at().equals(CAP)).findFirst().orElseThrow().remembered()).isFalse();
        // the capital is owned, so it is full, current, and appears exactly once
        assertThat(v.sectors().stream().filter(s -> s.at().equals(CAP)).count()).isEqualTo(1);
    }
}
