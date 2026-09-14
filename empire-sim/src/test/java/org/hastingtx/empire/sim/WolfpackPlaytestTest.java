package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
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
 * The Wolfpack playtest of game 82, 2026-09-14: issues #197–#205, each as it was reported.
 */
class WolfpackPlaytestTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int PET = COM.index("pet"), GUN = COM.index("gun"), SHELL = COM.index("shell");
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOR = Hex.stepRaw(CAP, 0, 2);
    private static final Coord SHED = Hex.stepRaw(HARBOR, 3, 1);      // land beside the harbour
    private static final Coord SEA = Hex.stepRaw(CAP, 0, 5);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    private static World world(Map<String, Double> harbour, Map<String, Double> shed) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        w = w.withCountry(w.country(0).withCash(1_000_000).withLevels(new Levels(80, 0, 0, 0)));
        w = TestWorlds.own(w, CFG, HARBOR, "harbor", 100, 127, harbour, Map.of());
        return TestWorlds.own(w, CFG, SHED, "warehouse", 100, 127, shed, Map.of());
    }

    private static World ship(World w, String cls, Coord at, double fuel, double crew) {
        Ship s = new Ship(w.nextShipId(), 0, cls, "", at, 100, Stocks.zero(COM.size()), null, null, 0, "", 0, null, null, fuel, crew);
        List<Ship> ships = new ArrayList<>(w.ships());
        ships.add(s);
        return w.withShips(ships, s.id() + 1);
    }

    /** #204: a full enlistment centre made no military, ever — civilian room capped a military output. */
    @Test
    void aFullEnlistmentCentreStillMakesMilitary() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        w = w.withCountry(w.country(0).withCash(100000));
        Coord e = Hex.stepRaw(CAP, 1, 1);
        w = TestWorlds.own(w, CFG, e, "enlistment_center", 100, 127, Map.of("civ", 1000.0, "food", 5000.0), Map.of());
        double before = w.sector(e).stock().get(COM.mil);
        World n = Update.run(w, CFG, 1).next();
        assertThat(n.sector(e).stock().get(COM.mil)).as("military enlisted at 1000/1000 civilians").isGreaterThan(before);
    }

    /** #202: the harbour ran dry mid-update while the warehouse beside it stood full. */
    @Test
    void aShipRefuelsFromTheWarehouseBesideAnEmptyHarbour() {
        World w = ship(world(Map.of("civ", 500.0, "food", 300.0), Map.of("pet", 1000.0)), "cargo_ship", HARBOR, 0, 10);
        Ship s = Update.run(w, CFG, 2).next().ship(1);
        assertThat(s.fuel()).isEqualTo(CFG.units().ships().shipClass("cargo_ship").tankOr0());
    }

    /** #203: crew only came from the harbour's own military. */
    @Test
    void aWarshipSignsOnCrewFromTheWarehouseBeside() {
        World w = ship(world(Map.of("civ", 500.0, "food", 300.0, "pet", 2000.0), Map.of("mil", 200.0)), "destroyer", HARBOR, 250, 0);
        Ship s = Update.run(w, CFG, 3).next().ship(1);
        assertThat(s.crew()).isEqualTo(CFG.units().ships().shipClass("destroyer").crewOr0());
    }

    /** #197: guns and shells had to be in the harbour itself, and a refusal did not say where any were. */
    @Test
    void aWarshipIsBuiltFromTheWarehouseBesideAndARefusalSaysWhereTheGunsAre() {
        World w = world(Map.of("civ", 500.0, "lcm", 500.0, "hcm", 500.0, "oil", 200.0), Map.of("gun", 50.0, "shell", 200.0, "mil", 10.0));
        CommandResult r = EX.execute(w, 0, new Command.BuildShip(HARBOR, "destroyer", null));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().sector(SHED).stock().get(GUN)).as("guns from the shed").isEqualTo(40);
        assertThat(r.info()).as("and a warning that ten military cannot crew her").contains("needs 50.0 mil");

        Coord plant = Hex.stepRaw(CAP, 2, 2);
        World far = TestWorlds.own(world(Map.of("civ", 500.0, "lcm", 500.0, "hcm", 500.0, "oil", 200.0, "shell", 200.0), Map.of()), CFG, plant, "light_manufacturing", 100, 127, Map.of("gun", 37.0), Map.of());
        CommandResult refused = EX.execute(far, 0, new Command.BuildShip(HARBOR, "destroyer", null));
        assertThat(refused.error()).contains("nearest stock is 37.0 gun at " + plant);
    }

    /** #198: a ship stayed dry until the update even after her quay was stocked. */
    @Test
    void aSailFromPortTopsUpTheTankThere() {
        World w = ship(world(Map.of("civ", 500.0, "food", 300.0, "pet", 500.0), Map.of()), "cargo_ship", HARBOR, 0, 10);
        w = w.withShip(w.ship(1).withMobility(10));
        CommandResult r = EX.execute(w, 0, new Command.Sail(1, SEA));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().ship(1).at()).as("she left the same turn").isNotEqualTo(HARBOR);
        assertThat(r.world().sector(HARBOR).stock().get(PET)).isLessThan(500);
    }

    /** #199, #201: sail ended a fishing mission (and said "fishing mission ended"). It pauses it now. */
    @Test
    void aSailPausesAStandingOrderAndItResumesOnArrival() {
        World w = ship(world(Map.of("civ", 500.0, "food", 300.0, "pet", 2000.0), Map.of()), "fishing_boat", HARBOR, 60, 5);
        w = w.withShip(w.ship(1).withMission(Ship.FISH, HARBOR).withMobility(0));
        for (int d = 0; d < 6; d++) for (int k = 1; k <= 3; k++) {
            Coord c = Hex.stepRaw(HARBOR, d, k);
            if (w.inBounds(c) && w.sector(c).terrain() == Terrain.OCEAN) w = w.withSector(w.sector(c).withTerrain(Terrain.OCEAN, 0, new Resources(50, 0, 0, 0, 0)));
        }
        Coord away = Hex.stepRaw(CAP, 1, 4);
        CommandResult r = EX.execute(w, 0, new Command.Sail(1, away));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).contains("her fishing resumes").doesNotContain("ended").doesNotContain("mineing");
        Ship s = r.world().ship(1);
        assertThat(s.fishing()).as("still her order").isTrue();
        assertThat(s.handLeg()).as(r.info() + " / " + s).isTrue();
        World cur = r.world();
        boolean arrived = false, fishingAgain = false;
        for (int i = 0; i < 8 && !fishingAgain; i++) {
            cur = Update.run(cur, CFG, 10 + i).next();
            Ship b = cur.ship(1);
            if (b.at().equals(away) && !b.handLeg()) arrived = true;
            if (arrived && b.note().contains("fished")) fishingAgain = true;
        }
        assertThat(arrived).as("the hand leg took her where she was sent").isTrue();
        assertThat(fishingAgain).as("and then her fishing took her back to the grounds").isTrue();
    }

    /** #205: sail wiped a warship's patrol. It pauses it too. */
    @Test
    void aSailPausesAPatrol() {
        World w = ship(world(Map.of("civ", 500.0, "mil", 500.0, "food", 300.0, "pet", 2000.0, "gun", 50.0, "shell", 500.0), Map.of()), "destroyer", HARBOR, 250, 50);
        Coord a = Hex.stepRaw(CAP, 0, 5), b = Hex.stepRaw(CAP, 1, 5);
        CommandResult p = EX.execute(w, 0, new Command.Mission(1, "patrol", List.of(a, b), 0, false));
        assertThat(p.error()).as(p.error()).isNull();
        CommandResult s = EX.execute(p.world(), 0, new Command.Sail(1, Hex.stepRaw(CAP, 2, 5)));
        assertThat(s.world().ship(1).mission()).isEqualTo("patrol");
        assertThat(s.info()).contains("her patrol resumes");
        assertThat(EX.execute(s.world(), 0, new Command.Mission(1, "patrol", List.of(), 0, true)).world().ship(1).mission()).as("off still ends it").isNull();
    }
}
