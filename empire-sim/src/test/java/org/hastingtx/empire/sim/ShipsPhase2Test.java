package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Issue #67, ships phase 2: lanes that feed thresholds and act on arrival, the supply mission, tankers
 * that drink from their own hold, and a logbook a line at a time.
 *
 * <p>Cargo here is lcm and hcm, never food: the harbours are full of people who eat, and a food number
 * would be measuring the dinner as much as the ship. The harbours have no distribution centre, so
 * nothing but a ship moves anything between them.
 */
class ShipsPhase2Test {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int LCM = COM.index("lcm"), HCM = COM.index("hcm"), PET = COM.index("pet"), OIL = COM.index("oil");
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOR_E = Hex.stepRaw(CAP, 0, 2), HARBOR_W = Hex.stepRaw(CAP, 3, 2);
    private static final Coord OFF_W = Hex.stepRaw(CAP, 3, 3);     // the sea hex just outside the western harbour

    /** Two harbours on opposite sides of the disc, the eastern one stocked, the western one wanting. */
    private static World world(Map<String, Double> eastStock, Map<String, Double> westStock, Map<String, Double> westThresholds) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        w = w.withCountry(w.country(0).withCash(100000));
        w = TestWorlds.own(w, CFG, HARBOR_E, "harbor", 100, 127, withPeople(eastStock), Map.of());
        w = TestWorlds.own(w, CFG, HARBOR_W, "harbor", 100, 127, withPeople(westStock), westThresholds);
        for (Coord h : new Coord[] {HARBOR_E, HARBOR_W}) w = w.withSector(w.sector(h).withDistCenter(null));
        return w;
    }
    private static Map<String, Double> withPeople(Map<String, Double> stock) {
        Map<String, Double> m = new java.util.HashMap<>(stock);
        m.putIfAbsent("civ", 500.0);
        m.putIfAbsent("food", 300.0);
        return m;
    }

    /** Add a hull with a full tank and a full crew; these tests are not about fuel or musters. */
    private static World addShip(World w, String cls, Coord at) {
        var c = CFG.units().ships().shipClass(cls);
        Ship s = new Ship(w.nextShipId(), 0, cls, "", at, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null, c.tankOr0(), c.crewOr0());
        List<Ship> ships = new ArrayList<>(w.ships());
        ships.add(s);
        return w.withShips(ships, s.id() + 1);
    }

    private static World run(World w, Command cmd) {
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, cmd);
        assertThat(r.error()).as(r.error()).isNull();
        return r.world();
    }

    // ---- lanes as distribution links ----

    @Test
    void aLaneWithNoCargoNamedCarriesOnlyWhatTheFarEndIsShortOf() {
        World w = addShip(world(Map.of("lcm", 900.0, "hcm", 400.0), Map.of("lcm", 100.0), Map.of("lcm", 400.0)), "cargo_ship", HARBOR_E);
        w = run(w, new Command.Lane(1, HARBOR_E, HARBOR_W, List.of()));
        World n = Update.run(w, CFG, 1).next();
        Ship s = n.ship(1);
        assertThat(s.stock().get(LCM)).as("the west is 300 short").isCloseTo(300, within(1e-9));
        assertThat(s.stock().get(HCM)).as("nobody asked for hcm").isZero();
        assertThat(s.lane().outbound()).isTrue();
    }

    @Test
    void aLaneWaitsInHarbourWhenTheFarEndWantsNothing() {
        World w = addShip(world(Map.of("lcm", 900.0), Map.of("lcm", 500.0), Map.of("lcm", 400.0)), "cargo_ship", HARBOR_E);
        w = run(w, new Command.Lane(1, HARBOR_E, HARBOR_W, List.of()));
        World n = Update.run(w, CFG, 1).next();
        assertThat(n.ship(1).at()).isEqualTo(HARBOR_E);
        assertThat(n.ship(1).lane().outbound()).isFalse();
        assertThat(n.ship(1).note()).contains("waiting");
    }

    @Test
    void aLaneUnloadsTheUpdateItArrives() {
        World w = addShip(world(Map.of(), Map.of("lcm", 0.0), Map.of()), "cargo_ship", OFF_W);
        Ship s = w.ship(1);
        w = w.withShip(s.withStock(s.stock().with(LCM, 300)).withLane(new Ship.Lane(HARBOR_E, HARBOR_W, List.of(LCM), true)).withDest(HARBOR_W));
        World n = Update.run(w, CFG, 1).next();
        assertThat(n.ship(1).at()).isEqualTo(HARBOR_W);
        assertThat(n.ship(1).stock().get(LCM)).as("landed on arrival, not an update later").isZero();
        assertThat(n.sector(HARBOR_W).stock().get(LCM)).isCloseTo(300, within(1e-9));
        assertThat(n.ship(1).lane().outbound()).as("already turned for home").isFalse();
        assertThat(n.ship(1).dest()).isEqualTo(HARBOR_E);
    }

    // ---- the supply mission ----

    @Test
    void aSupplyShipFillsAShortHarbourFromOneThatCanSpareIt() {
        World w = addShip(world(Map.of("lcm", 900.0, "hcm", 400.0), Map.of("lcm", 50.0), Map.of("lcm", 400.0)), "cargo_ship", HARBOR_E);
        w = run(w, new Command.Supply(1, null, false));
        World n = Update.run(w, CFG, 1).next();
        assertThat(n.ship(1).stock().get(LCM)).as("exactly the shortfall").isCloseTo(350, within(1e-9));
        assertThat(n.ship(1).stock().get(HCM)).isZero();
        assertThat(n.ship(1).dest()).isEqualTo(HARBOR_W);
        boolean landed = false;
        for (int i = 0; i < 10 && !landed; i++) {
            n = Update.run(n, CFG, 2 + i).next();
            landed = n.ship(1).note().contains("unloaded");
        }
        assertThat(landed).isTrue();
        assertThat(n.sector(HARBOR_W).stock().get(LCM)).as("topped up to its threshold, give or take a refit").isBetween(380.0, 400.0);
        assertThat(n.ship(1).supplying()).isTrue();
    }

    @Test
    void twoSupplyShipsDoNotBothAnswerTheSameShortage() {
        World w = world(Map.of("lcm", 900.0), Map.of("lcm", 50.0), Map.of("lcm", 400.0));
        w = addShip(addShip(w, "cargo_ship", HARBOR_E), "cargo_ship", HARBOR_E);
        w = run(run(w, new Command.Supply(1, null, false)), new Command.Supply(2, null, false));
        World n = Update.run(w, CFG, 1).next();
        assertThat(n.ship(1).stock().get(LCM) + n.ship(2).stock().get(LCM)).isCloseTo(350, within(1e-9));
        assertThat(n.ship(2).note()).contains("waiting");
    }

    @Test
    void aTankerOnSupplyCarriesOnlyWhatATankerCarries() {
        World w = world(Map.of("lcm", 900.0, "oil", 900.0, "pet", 900.0), Map.of("lcm", 0.0, "oil", 0.0), Map.of("lcm", 400.0, "oil", 300.0));
        w = addShip(w, "tanker", HARBOR_E);
        w = run(w, new Command.Supply(1, null, false));
        World n = Update.run(w, CFG, 1).next();
        assertThat(n.ship(1).stock().get(OIL)).isCloseTo(300, within(1e-9));
        assertThat(n.ship(1).stock().get(LCM)).isZero();
        assertThat(n.ship(1).stock().get(PET)).as("nobody is short of petrol").isZero();
    }

    @Test
    void aSupplyShipWithNothingToDoWaitsInHarbour() {
        World w = addShip(world(Map.of("lcm", 900.0), Map.of("lcm", 500.0), Map.of("lcm", 400.0)), "cargo_ship", HARBOR_E);
        w = run(w, new Command.Supply(1, null, false));
        World n = Update.run(w, CFG, 1).next();
        assertThat(n.ship(1).at()).isEqualTo(HARBOR_E);
        assertThat(n.ship(1).dest()).isNull();
        assertThat(n.ship(1).note()).contains("waiting");
    }

    @Test
    void supplyNeedsAHullThatCarriesAndAHome() {
        World w = world(Map.of(), Map.of(), Map.of());
        assertThat(new CommandExecutor(CFG).execute(addShip(w, "fishing_boat", HARBOR_E), 0, new Command.Supply(1, null, false)).error()).contains("carries no cargo");
        assertThat(new CommandExecutor(CFG).execute(addShip(w, "cargo_ship", OFF_W), 0, new Command.Supply(1, null, false)).error()).contains("home harbour");
        World on = run(addShip(w, "cargo_ship", HARBOR_E), new Command.Supply(1, null, false));
        assertThat(run(on, new Command.Supply(1, null, true)).ship(1).supplying()).isFalse();
        assertThat(run(on, new Command.Sail(1, OFF_W)).ship(1).supplying()).as("a sail order ends the mission").isFalse();
    }

    // ---- tankers ----

    @Test
    void aTankerWithPetrolAboardNeverSitsDeadInTheWater() {
        World w = addShip(world(Map.of(), Map.of(), Map.of()), "tanker", OFF_W);
        Ship s = w.ship(1);
        w = w.withShip(s.withFuel(0).withStock(s.stock().with(PET, 1000)).withDest(Hex.stepRaw(CAP, 3, 6)));
        World n = Update.run(w, CFG, 1).next();
        assertThat(n.ship(1).at()).as("she sailed").isNotEqualTo(OFF_W);
        assertThat(n.ship(1).stock().get(PET)).isLessThan(1000);
        assertThat(n.ship(1).note()).contains("from the hold");
    }

    // ---- the logbook ----

    @Test
    void aShipKeepsItsUpdateALineAtATime() {
        World w = addShip(world(Map.of("lcm", 900.0), Map.of("lcm", 50.0), Map.of("lcm", 400.0)), "cargo_ship", HARBOR_E);
        w = run(w, new Command.Supply(1, null, false));
        UpdateResult u = Update.run(w, CFG, 1);
        List<String> lines = u.shipNotes().get(1L);
        assertThat(lines).hasSizeGreaterThan(1);
        assertThat(lines).anyMatch(l -> l.startsWith("loaded")).anyMatch(l -> l.startsWith("bound for"));
        assertThat(String.join("; ", lines)).isEqualTo(u.next().ship(1).note());
    }
}
