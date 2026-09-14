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
 * Ships burn petrol (issue #65).
 *
 * <p>The point of the feature is economic, not mechanical: until now a refinery turned oil into a
 * commodity nothing in the world consumed. These tests check the loop closes — fuel comes out of a
 * harbour's stock, goes into a tank, and leaves the world by the hex — and that a dry tank actually
 * stops a ship, because a fuel system nobody can run out of is decoration.
 */
class ShipFuelTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int PET = COM.index("pet");
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOR = Hex.stepRaw(CAP, 0, 2);
    private static final Coord FAR_SEA = Hex.stepRaw(CAP, 0, 9);

    private static World world(double harbourPet) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        w = w.withCountry(w.country(0).withCash(100000));
        return TestWorlds.own(w, CFG, HARBOR, "harbor", 100, 127,
                Map.of("civ", 500.0, "food", 300.0, "lcm", 500.0, "pet", harbourPet), Map.of());
    }

    /** Fully crewed, so what these tests measure is fuel and nothing else (crews are issue #66). */
    private static World withShip(World w, Coord at, double fuel) {
        double crew = CFG.units().ships().shipClass("cargo_ship").crewOr0();
        Ship s = new Ship(w.nextShipId(), 0, "cargo_ship", "", at, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null, fuel, crew);
        return w.withShips(List.of(s), s.id() + 1);
    }

    @Test
    void fuelIsOnAndEveryHullHasATank() {
        assertThat(CFG.units().ships().fuel()).isTrue();
        for (var cls : CFG.units().ships().classes())
            assertThat(cls.tankOr0()).describedAs("%s has a tank", cls.id()).isGreaterThan(0);
    }

    @Test
    void aHarbourFillsTheTankFromItsOwnStock() {
        World w = withShip(world(500), HARBOR, 0);
        double before = w.sector(HARBOR).stock().get(PET);

        World after = Update.run(w, CFG, 3).next();

        double tank = CFG.units().ships().shipClass("cargo_ship").tankOr0();
        assertThat(after.ships().get(0).fuel()).describedAs("the tank filled").isEqualTo(tank);
        assertThat(after.sector(HARBOR).stock().get(PET))
                .describedAs("and the harbour paid for it out of its own stock")
                .isEqualTo(before - tank);
    }

    @Test
    void aDryTankHoldsTheShipWhereItIs() {
        // at sea, no fuel, nowhere to get any, and somewhere it wants to be
        World w = withShip(world(0), Hex.stepRaw(CAP, 0, 3), 0);
        w = w.withShip(w.ships().get(0).withDest(FAR_SEA));
        Coord was = w.ships().get(0).at();

        World after = Update.run(w, CFG, 3).next();

        assertThat(after.ships().get(0).at()).describedAs("it did not move").isEqualTo(was);
        assertThat(after.ships().get(0).note()).contains("out of fuel");
    }

    @Test
    void sailingBurnsFuelByTheHexAndTheWorldLosesIt() {
        World w = withShip(world(0), Hex.stepRaw(CAP, 0, 3), 100);
        w = w.withShip(w.ships().get(0).withDest(FAR_SEA));
        double perHex = CFG.units().ships().shipClass("cargo_ship").fuelPerHexOr0();

        var r = Update.run(w, CFG, 3);
        Ship after = r.next().ships().get(0);
        int hops = Hex.distance(w, Hex.stepRaw(CAP, 0, 3), after.at());

        assertThat(hops).describedAs("it sailed").isGreaterThan(0);
        assertThat(after.fuel()).describedAs("and burned fuel for every hex").isEqualTo(100 - hops * perHex);
        // the update completing at all is the conservation proof: apply throws if the books are out by a unit
        assertThat(r.next().updateNumber()).isEqualTo(w.updateNumber() + 1);
    }

    @Test
    void afuelledShipOnlyGetsAsFarAsItsTankAllows() {
        // fuel for exactly one hex, against a range of three
        double perHex = CFG.units().ships().shipClass("cargo_ship").fuelPerHexOr0();
        World w = withShip(world(0), Hex.stepRaw(CAP, 0, 3), perHex);
        w = w.withShip(w.ships().get(0).withDest(FAR_SEA));

        World after = Update.run(w, CFG, 3).next();
        int hops = Hex.distance(w, Hex.stepRaw(CAP, 0, 3), after.ships().get(0).at());
        assertThat(hops).describedAs("one hex of fuel, one hex sailed").isEqualTo(1);
        assertThat(after.ships().get(0).fuel()).isZero();
    }

    @Test
    void aTankerRefuelsAnotherShipAtSea() {
        Coord meet = Hex.stepRaw(CAP, 0, 3);
        World w = world(0);
        // a tanker with petrol in its hold, and a dry cargo ship alongside
        Ship tanker = new Ship(1, 0, "tanker", "", meet, 100, Stocks.of(new double[COM.size()]).with(PET, 500), null, null, 0, "", 0, null, null, 200,
                CFG.units().ships().shipClass("tanker").crewOr0());
        Ship dry = new Ship(2, 0, "cargo_ship", "", meet, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null, 0,
                CFG.units().ships().shipClass("cargo_ship").crewOr0());
        w = w.withShips(List.of(tanker, dry), 3);

        World after = Update.run(w, CFG, 3).next();

        Ship refuelled = after.ships().stream().filter(s -> s.id() == 2).findFirst().orElseThrow();
        Ship gave = after.ships().stream().filter(s -> s.id() == 1).findFirst().orElseThrow();
        assertThat(refuelled.fuel()).describedAs("the tanker filled it").isGreaterThan(0.0);
        assertThat(gave.stock().get(PET)).describedAs("out of its own hold").isLessThan(500.0);
        assertThat(gave.stock().get(PET) + refuelled.fuel())
                .describedAs("and nothing was created in the transfer").isEqualTo(500.0);
    }

    // ---- Richard 2026-09-14: "all of the ships ran out of fuel" ----

    @Test
    void aShipDoesNotLeavePortUntilHerTankIsFull() {
        World w = withShip(world(50), HARBOR, 0);
        w = w.withShip(w.ships().get(0).withDest(FAR_SEA));
        World after = Update.run(w, CFG, 3).next();
        Ship s = after.ships().get(0);
        assertThat(s.at()).as("still in port").isEqualTo(HARBOR);
        assertThat(s.fuel()).as("took what the harbour had").isEqualTo(50);
        assertThat(s.note()).contains("waiting in harbour to fill her tank").contains("no pet left");

        // send the harbour petrol and she tops up and goes
        World stocked = after.withSector(after.sector(HARBOR).withStock(after.sector(HARBOR).stock().with(PET, 500)));
        Ship gone = Update.run(stocked, CFG, 4).next().ships().get(0);
        assertThat(gone.at()).as("full, she sails").isNotEqualTo(HARBOR);

        // an order given now waits for the tank too
        var r = new org.hastingtx.empire.engine.command.CommandExecutor(CFG).execute(w.withShip(w.ships().get(0).withDest(null).withMobility(10)), 0,
                new org.hastingtx.empire.engine.command.Command.Sail(1, FAR_SEA));
        assertThat(r.error()).isNull();
        assertThat(r.world().ships().get(0).at()).isEqualTo(HARBOR);
        assertThat(r.info()).contains("filling her tank");
    }

    @Test
    void aShipLowOnFuelTurnsForTheNearestHarbourByHerself() {
        Coord out = Hex.stepRaw(CAP, 0, 6);                     // four hexes from the harbour
        World low = withShip(world(500), out, 30);
        low = low.withShip(low.ships().get(0).withDest(FAR_SEA));
        Ship s = Update.run(low, CFG, 5).next().ships().get(0);
        assertThat(s.note()).contains("low on fuel");
        assertThat(Hex.distance(low, s.at(), HARBOR)).as("she turned back").isLessThan(4);

        World plenty = withShip(world(500), out, 120);
        plenty = plenty.withShip(plenty.ships().get(0).withDest(FAR_SEA));
        Ship p = Update.run(plenty, CFG, 5).next().ships().get(0);
        assertThat(p.note()).doesNotContain("low on fuel");
        assertThat(Hex.distance(plenty, p.at(), FAR_SEA)).as("with fuel to spare she carries on").isLessThan(3);
    }

    /**
     * The outcome, not the mechanism: game 82's fleet was caught out at sea on its missions with less fuel
     * than the trip home, a hold not yet full and a hull not yet worn, so nothing turned it for port and
     * it roamed on until the tank was dry. A boat in that position now comes home and keeps fishing.
     * Checked to fail with the low-fuel rule taken out.
     */
    @Test
    void aFishingBoatCaughtOutOnHerMissionComesHomeBeforeSheRunsDry() {
        World w = world(5000);
        for (int d = 0; d < 6; d++) for (int k = 1; k <= 6; k++) {
            Coord c = Hex.stepRaw(HARBOR, d, k);
            if (w.inBounds(c) && w.sector(c).terrain() == Terrain.OCEAN) w = w.withSector(w.sector(c).withTerrain(Terrain.OCEAN, 0, new Resources(1, 0, 0, 0, 0)));   // poor water: the hold will not bring her in
        }
        var fc = CFG.units().ships().shipClass("fishing_boat");
        Coord farOut = Hex.stepRaw(HARBOR, 0, 5);
        double sixHexes = 6 * fc.fuelPerHexOr0();
        Ship boat = new Ship(1, 0, "fishing_boat", "", farOut, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, Ship.FISH, HARBOR, sixHexes, fc.crewOr0());
        w = w.withShips(List.of(boat), 2);
        int dryAtSea = 0;
        boolean cameIn = false;
        for (int i = 0; i < 20; i++) {
            w = Update.run(w, CFG, 100 + i).next();
            Ship b = w.ship(1);
            if (b.at().equals(HARBOR)) cameIn = true;
            else if (b.fuel() < fc.fuelPerHexOr0()) dryAtSea++;
        }
        assertThat(dryAtSea).as("updates spent dry at sea").isZero();
        assertThat(cameIn).as("she came in to refuel").isTrue();
        assertThat(w.ship(1).mission()).as("and is still fishing").isEqualTo(Ship.FISH);
    }
}
