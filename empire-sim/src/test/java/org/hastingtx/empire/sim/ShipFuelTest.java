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

    private static World withShip(World w, Coord at, double fuel) {
        Ship s = new Ship(w.nextShipId(), 0, "cargo_ship", "", at, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null, fuel);
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
        Ship tanker = new Ship(1, 0, "tanker", "", meet, 100, Stocks.of(new double[COM.size()]).with(PET, 500), null, null, 0, "", 0, null, null, 200);
        Ship dry = new Ship(2, 0, "cargo_ship", "", meet, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null, 0);
        w = w.withShips(List.of(tanker, dry), 3);

        World after = Update.run(w, CFG, 3).next();

        Ship refuelled = after.ships().stream().filter(s -> s.id() == 2).findFirst().orElseThrow();
        Ship gave = after.ships().stream().filter(s -> s.id() == 1).findFirst().orElseThrow();
        assertThat(refuelled.fuel()).describedAs("the tanker filled it").isGreaterThan(0.0);
        assertThat(gave.stock().get(PET)).describedAs("out of its own hold").isLessThan(500.0);
        assertThat(gave.stock().get(PET) + refuelled.fuel())
                .describedAs("and nothing was created in the transfer").isEqualTo(500.0);
    }
}
