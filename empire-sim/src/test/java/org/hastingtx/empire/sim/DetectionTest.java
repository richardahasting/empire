package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.view.CountryView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #75: radar and lookouts find enemy ships, and the sightings age.
 *
 * <p>Alongside the invariants there are calibration probes — the playbook's rule that conservation
 * tests can pass while the outcome is dead. A detection model that never detects anything satisfies
 * every invariant, so these assert the thing actually happens, and that a submarine is meaningfully
 * harder to find than a freighter at the same range.
 */
class DetectionTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;

    /** Country 0 with a radar station at its capital; country 1 owns nothing but the hulls we place. */
    private static World twoCountries(double radarLevel) {
        World w = TestWorlds.disc(CFG, 2, java.util.Map.of("civ", 500.0, "food", 2000.0));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Ruritania", new Coord(1, 1), 100000, 640, Levels.ZERO, org.hastingtx.empire.engine.config.HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        return w.withSector(w.sector(CAP).withRadarLevel(radarLevel));
    }

    private static World withEnemyShip(World w, String cls, Coord at) {
        List<Ship> ships = new ArrayList<>(w.ships());
        long id = w.nextShipId();
        ships.add(new Ship(id, 1, cls, "", at, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null));
        return w.withShips(ships, id + 1);
    }

    /** How often country 0 ends up holding a contact on the ship, over many seeds. */
    private static int sightingsOver(World w, int seeds) {
        int hits = 0;
        for (int seed = 1; seed <= seeds; seed++) if (!Update.run(w, CFG, seed).next().contactsOf(0).isEmpty()) hits++;
        return hits;
    }

    @Test
    void radarFindsAShipCloseInAndMissesOneFarOut() {
        // PROBE: the feature's whole point. Inside the nominal range of 12.4 a freighter is usually seen;
        // at nearly twice that range it is never seen. The near rate tops out near 0.6 rather than 1.0
        // because target_signature.single_unit is 0.6 — one hull in one update is a glimpse, not a fix.
        World near = withEnemyShip(twoCountries(100), "cargo_ship", Hex.stepRaw(CAP, 0, 3));
        World far = withEnemyShip(twoCountries(100), "cargo_ship", Hex.stepRaw(CAP, 0, 22));
        assertThat(sightingsOver(near, 40)).isBetween(16, 34);
        assertThat(sightingsOver(far, 40)).isEqualTo(0);
    }

    @Test
    void aShipThatLoitersInRangeIsFoundSoonerOrLater() {
        // PROBE at the outcome, not the wire: one update is a coin weighted 0.6, but a freighter that sits
        // inside the radar's reach must not be able to loiter there update after update unnoticed.
        World w = withEnemyShip(twoCountries(100), "cargo_ship", Hex.stepRaw(CAP, 0, 3));
        for (int i = 0; i < 5 && w.contactsOf(0).isEmpty(); i++) w = Update.run(w, CFG, 3).next();
        assertThat(w.contactsOf(0)).isNotEmpty();
    }

    @Test
    void aSubmarineIsMuchHarderToFindThanAFreighterAtTheSameRange() {
        // PROBE: submarines are the reason detection exists; if the signature did nothing this passes anyway
        // with equal counts, so assert the gap, not merely that both are detectable.
        Coord at = Hex.stepRaw(CAP, 0, 9);
        int freighter = sightingsOver(withEnemyShip(twoCountries(100), "cargo_ship", at), 60);
        int submarine = sightingsOver(withEnemyShip(twoCountries(100), "submarine", at), 60);
        assertThat(freighter).isGreaterThan(submarine);
        assertThat(submarine).isLessThan(freighter / 2);
    }

    @Test
    void noRadarAndNoShipsMeansNoContacts() {
        World w = withEnemyShip(twoCountries(0), "cargo_ship", Hex.stepRaw(CAP, 0, 3));
        assertThat(Update.run(w, CFG, 1).next().contactsOf(0)).isEmpty();
    }

    @Test
    void aContactRemembersWhereTheShipWasAndIsDroppedOnceStale() {
        World w = withEnemyShip(twoCountries(100), "cargo_ship", Hex.stepRaw(CAP, 0, 3));
        World seen = w;
        for (int seed = 1; seed <= 40 && seen.contactsOf(0).isEmpty(); seed++) seen = Update.run(w, CFG, seed).next();
        assertThat(seen.contactsOf(0)).isNotEmpty();
        Contact c = seen.contactsOf(0).get(0);
        assertThat(c.at()).isEqualTo(Hex.stepRaw(CAP, 0, 3));
        assertThat(c.targetOwner()).isEqualTo(1);
        assertThat(c.cls()).isEqualTo("cargo_ship");

        // take the ship away: the sighting is kept, ageing, then dropped once past the staleness window
        World gone = seen.withShips(List.of());
        int staleness = CFG.detection().contactStalenessUpdates();
        for (int i = 0; i < staleness; i++) { gone = Update.run(gone, CFG, 7).next(); assertThat(gone.contactsOf(0)).isNotEmpty(); }
        gone = Update.run(gone, CFG, 7).next();
        assertThat(gone.contactsOf(0)).isEmpty();
    }

    @Test
    void detectionIsDeterministicAndIndependentOfShipOrder() {
        // order-independence: the same fleet listed backwards must give the same contacts
        World a = twoCountries(100);
        a = withEnemyShip(a, "cargo_ship", Hex.stepRaw(CAP, 0, 4));
        a = withEnemyShip(a, "cargo_ship", Hex.stepRaw(CAP, 2, 5));
        a = withEnemyShip(a, "submarine", Hex.stepRaw(CAP, 4, 3));
        List<Ship> reversed = new ArrayList<>(a.ships());
        java.util.Collections.reverse(reversed);
        World b = a.withShips(reversed, a.nextShipId());

        assertThat(Update.run(a, CFG, 5).next().contacts()).isEqualTo(Update.run(a, CFG, 5).next().contacts());
        assertThat(Update.run(b, CFG, 5).next().contacts()).isEqualTo(Update.run(a, CFG, 5).next().contacts());
    }

    @Test
    void aContactReachesTheOwnerButNeverTheCountryItIsAbout() {
        World w = withEnemyShip(twoCountries(100), "cargo_ship", Hex.stepRaw(CAP, 0, 3));
        World seen = w;
        for (int seed = 1; seed <= 40 && seen.contactsOf(0).isEmpty(); seed++) seen = Update.run(w, CFG, seed).next();
        assertThat(seen.contactsOf(0)).isNotEmpty();
        CountryView watcher = CountryView.of(seen, CFG, 0);
        assertThat(watcher.contacts()).hasSize(1);
        assertThat(watcher.contacts().get(0).ownerName()).isEqualTo("Ruritania");
        // the country being watched learns nothing: a contact belongs to its holder alone
        assertThat(CountryView.of(seen, CFG, 1).contacts()).isEmpty();
    }
}
