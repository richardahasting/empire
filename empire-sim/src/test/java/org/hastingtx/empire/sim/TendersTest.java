package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #182, Richard 2026-09-14: "a tender can hear a distress call and go refuel or fix a ship ... the
 * tender is slow and goes to and from ships in distress and their home harbor." Every update here also
 * passes the apply step's conservation check: petrol only moves from hold to tank, and patching lcm is
 * tallied as destroyed.
 */
class TendersTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int PET = COM.index("pet"), LCM = COM.index("lcm");
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOR = Hex.stepRaw(CAP, 0, 2);
    private static final Coord OUT = Hex.stepRaw(CAP, 0, 6);          // four hexes out from the harbour

    private static World world() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        w = w.withCountry(w.country(0).withCash(100000));
        return TestWorlds.own(w, CFG, HARBOR, "harbor", 100, 127, Map.of("civ", 800.0, "food", 300.0, "lcm", 2000.0, "pet", 5000.0), Map.of());
    }

    private static World add(World w, String cls, Coord at, double fuel, double eff) {
        var c = CFG.units().ships().shipClass(cls);
        Ship s = new Ship(w.nextShipId(), 0, cls, "", at, eff, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null, fuel, c.crewOr0());
        List<Ship> ships = new ArrayList<>(w.ships());
        ships.add(s);
        return w.withShips(ships, s.id() + 1);
    }

    private static World stocked(World w, long id) {
        var c = CFG.units().ships().shipClass("tender");
        Ship t = w.ship(id);
        return w.withShip(t.withStock(t.stock().with(PET, 400).with(LCM, 150)).withFuel(c.tankOr0()));
    }

    @Test
    void aStrandedShipCallsAndATenderFillsHerTankAndGoesHome() {
        World w = add(world(), "cargo_ship", OUT, 0, 100);                // #1, dry at sea
        w = stocked(add(w, "tender", HARBOR, 0, 100), 2);                  // #2, waiting in harbour
        double tank = CFG.units().ships().shipClass("cargo_ship").tankOr0();
        boolean called = false, answered = false, refilled = false, home = false;
        for (int i = 0; i < 16 && !home; i++) {
            w = Update.run(w, CFG, 10 + i).next();
            if (w.ship(1).note().contains("distress call sent")) called = true;
            if (w.ship(2).rescuing()) answered = true;
            if (w.ship(1).fuel() >= tank - 1e-9) refilled = true;
            if (refilled && w.ship(2).at().equals(HARBOR) && w.ship(2).mission() == null) home = true;
        }
        assertThat(answered).as("a tender took the call").isTrue();
        assertThat(refilled).as("and filled her tank").isTrue();
        assertThat(home).as("and went home, free for the next call").isTrue();
        assertThat(called || answered).isTrue();
    }

    @Test
    void aHullBelowTheLimpLineIsPatchedWithLcmFromTheTender() {
        double floor = CFG.units().ships().limpFloor(CFG.units().ships().shipClass("cargo_ship"), 0);
        World w = add(world(), "cargo_ship", Hex.stepRaw(CAP, 0, 3), 0, 5);   // a hex off the harbour, dry and battered to 5%
        w = stocked(add(w, "tender", HARBOR, 0, 100), 2);
        for (int i = 0; i < 4 && w.ship(1).efficiency() < floor - 1e-9; i++) w = Update.run(w, CFG, 40 + i).next();
        assertThat(w.ship(1).efficiency()).as("patched to the limp line").isGreaterThanOrEqualTo(floor - 1e-9);
        assertThat(w.ship(2).stock().get(LCM)).as("with lcm from the tender's hold").isLessThan(150);
    }

    @Test
    void theNearerOfTwoTendersAnswers() {
        Coord farHarbour = Hex.stepRaw(CAP, 3, 2);
        World w = TestWorlds.own(world(), CFG, farHarbour, "harbor", 100, 127, Map.of("civ", 800.0, "food", 300.0, "lcm", 2000.0, "pet", 5000.0), Map.of());
        w = add(w, "cargo_ship", OUT, 0, 100);                             // #1, east of the disc
        w = stocked(add(w, "tender", farHarbour, 0, 100), 2);              // #2, the far side
        w = stocked(add(w, "tender", HARBOR, 0, 100), 3);                  // #3, next door
        World n = Update.run(w, CFG, 60).next();
        assertThat(n.ship(3).rescuing()).as("the near one went").isTrue();
        assertThat(n.ship(3).ward()).isEqualTo(1);
        assertThat(n.ship(2).rescuing()).as("the far one stayed").isFalse();
    }

    @Test
    void aTenderWaitingInHarbourRestocks() {
        World w = add(world(), "tender", HARBOR, 150, 100);
        Ship t = Update.run(w, CFG, 70).next().ship(1);
        assertThat(t.stock().get(PET)).isEqualTo(400);
        assertThat(t.stock().get(LCM)).isEqualTo(150);
    }

    @Test
    void aCallThatNoLongerNeedsAnsweringSendsHerHome() {
        World w = add(world(), "cargo_ship", OUT, 0, 100);
        w = stocked(add(w, "tender", HARBOR, 0, 100), 2);
        w = Update.run(w, CFG, 80).next();
        assertThat(w.ship(2).rescuing()).isTrue();
        // somebody else fills her in the meantime
        w = w.withShip(w.ship(1).withFuel(100));
        World n = Update.run(w, CFG, 81).next();
        assertThat(n.ship(2).rescuing()).isFalse();
        assertThat(n.ship(2).note()).contains("no longer needs her");
    }

    /** Richard 2026-09-14: a tender with no orders waits in port, not at sea, and is still on call on the way in. */
    @Test
    void anIdleTenderAtSeaGoesInToWaitAndAnswersCallsOnTheWay() {
        World w = stocked(add(world(), "tender", OUT, 0, 100), 1);     // idle, four hexes out
        World n = Update.run(w, CFG, 90).next();
        assertThat(n.ship(1).note()).contains("on call: making for");
        assertThat(n.ship(1).dest()).isEqualTo(HARBOR);

        // on her way in, a ship runs dry nearby: she answers
        World call = add(n, "cargo_ship", Hex.stepRaw(CAP, 1, 5), 0, 100);
        World m = Update.run(call, CFG, 91).next();
        assertThat(m.ship(1).rescuing()).as("still on call while heading in").isTrue();
    }
}
