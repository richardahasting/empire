package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hull needs people aboard (issue #66).
 *
 * <p>The interesting part is not that a ship refuses to sail — it is where the people come from. A crew
 * is drawn out of the harbour's own population, so a fleet and a factory compete for the same
 * civilians, and a hull broken up gives them back.
 */
class ShipCrewTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOR = Hex.stepRaw(CAP, 0, 2);
    private static final Coord FAR_SEA = Hex.stepRaw(CAP, 0, 9);
    private static final double CREW = CFG.units().ships().shipClass("cargo_ship").crewOr0();
    private static final double TANK = CFG.units().ships().shipClass("cargo_ship").tankOr0();

    private static World world(double harbourCiv) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        w = w.withCountry(w.country(0).withCash(100000));
        return TestWorlds.own(w, CFG, HARBOR, "harbor", 100, 127,
                Map.of("civ", harbourCiv, "food", 300.0, "lcm", 500.0, "pet", 900.0), Map.of());
    }

    private static World withShip(World w, Coord at, double crew) {
        Ship s = new Ship(w.nextShipId(), 0, "cargo_ship", "", at, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null, TANK, crew);
        return w.withShips(List.of(s), s.id() + 1);
    }

    @Test
    void crewsAreOnAndEveryHullNeedsOne() {
        assertThat(CFG.units().ships().crews()).isTrue();
        for (var cls : CFG.units().ships().classes())
            assertThat(cls.crewOr0()).describedAs("%s needs a crew", cls.id()).isGreaterThan(0);
    }

    @Test
    void aMerchantmanMustersCiviliansAndAWarshipMusters() {
        var ships = CFG.units().ships();
        assertThat(ships.crewIsCivilian(ships.shipClass("cargo_ship"))).isTrue();
        assertThat(ships.crewIsCivilian(ships.shipClass("fishing_boat"))).isTrue();
        assertThat(ships.crewIsCivilian(ships.shipClass("destroyer"))).isFalse();
        assertThat(ships.crewIsCivilian(ships.shipClass("submarine"))).isFalse();
    }

    @Test
    void theHarbourSignsThemOnOutOfItsOwnPopulation() {
        // The harbour's population also grows during the update, so the honest comparison is against the
        // same world with no hull in it rather than against the starting number.
        World crewed = Update.run(withShip(world(500), HARBOR, 0), CFG, 3).next();
        World empty = Update.run(world(500), CFG, 3).next();

        assertThat(crewed.ships().get(0).crew()).describedAs("a full complement").isEqualTo(CREW);
        assertThat(crewed.sector(HARBOR).stock().get(COM.civ))
                .describedAs("and the harbour is exactly that many civilians poorer for it — a fleet competes with a factory")
                .isEqualTo(empty.sector(HARBOR).stock().get(COM.civ) - CREW);
    }

    @Test
    void aShortHandedShipStaysAtTheQuay() {
        // at sea with nobody aboard and somewhere to be: no harbour to muster from
        World w = withShip(world(0), Hex.stepRaw(CAP, 0, 3), 0);
        w = w.withShip(w.ships().get(0).withDest(FAR_SEA));
        Coord was = w.ships().get(0).at();

        World after = Update.run(w, CFG, 3).next();

        assertThat(after.ships().get(0).at()).isEqualTo(was);
        assertThat(after.ships().get(0).note()).contains("short-handed");
    }

    @Test
    void anEmptyHarbourCannotCrewHerAndSaysSo() {
        World w = withShip(world(0), HARBOR, 0);
        World after = Update.run(w, CFG, 3).next();
        assertThat(after.ships().get(0).crew()).isZero();
        assertThat(after.ships().get(0).note()).contains("no civ in the harbour");
    }

    @Test
    void breakingHerUpPutsTheCrewAndTheFuelAshore() {
        World w = withShip(world(500), HARBOR, CREW);
        double civBefore = w.sector(HARBOR).stock().get(COM.civ);
        double petBefore = w.sector(HARBOR).stock().get(COM.index("pet"));

        var r = new CommandExecutor(CFG).execute(w, 0, new Command.Scrap(w.ships().get(0).id()));
        assertThat(r.ok()).as(r.error()).isTrue();

        assertThat(r.world().sector(HARBOR).stock().get(COM.civ))
                .describedAs("scrapping a hull does not drown her crew").isEqualTo(civBefore + CREW);
        assertThat(r.world().sector(HARBOR).stock().get(COM.index("pet")))
                .describedAs("nor pour her fuel into the harbour").isEqualTo(petBefore + TANK);
        assertThat(r.world().ships()).isEmpty();
    }

    @Test
    void aCrewedShipSailsAndConservationHolds() {
        World w = withShip(world(500), HARBOR, CREW);
        w = w.withShip(w.ships().get(0).withDest(FAR_SEA));
        var r = Update.run(w, CFG, 3);
        // apply throws if the books are out by a unit, and a crew is people who left a sector
        assertThat(r.next().ships().get(0).at()).isNotEqualTo(HARBOR);
        assertThat(r.next().ships().get(0).crew()).isEqualTo(CREW);
    }
}
