package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #68, missions: where a warship goes. Patrol walks its route; every mission comes home for
 * supplies and goes back out; a blockade stops a hostile ship at war and nobody at peace; an escort
 * keeps with her charge; a search stays near home; an interdiction shells enemy trains.
 */
class MissionsTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int GUN = COM.index("gun"), SHELL = COM.index("shell"), FOOD = COM.food;
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOUR = Hex.stepRaw(CAP, 0, 2);
    private static final Coord A = Hex.stepRaw(CAP, 0, 5), B = Hex.stepRaw(CAP, 1, 5);
    private static final Coord THEIR_CAP = new Coord(2, 2);

    private static World world(boolean war) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        Sector theirs = w.sector(THEIR_CAP).withTerrain(Terrain.PLAINS, 100, new Resources(80, 40, 10, 10, 5))
                .withOwner(1).withDesignation("capital", 100).withMobility(127).withStock(Stocks.of(COM.fromMap(Map.of("civ", 500.0, "food", 400.0))));
        w = w.withSector(theirs);
        List<Country> countries = new ArrayList<>(w.countries());
        countries.set(0, w.country(0).withCash(100000));
        countries.add(new Country(1, "Them", THEIR_CAP, 100000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(countries);
        w = TestWorlds.own(w, CFG, HARBOUR, "harbor", 100, 127, Map.of("civ", 800.0, "mil", 800.0, "food", 300.0, "gun", 100.0, "shell", 1000.0, "pet", 2000.0, "lcm", 500.0), Map.of());
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        return w;
    }

    private static World ship(World w, int owner, String cls, Coord at) {
        var c = CFG.units().ships().shipClass(cls);
        Stocks st = Stocks.zero(COM.size());
        if (c.armed()) st = st.with(GUN, c.gunsOr0()).with(SHELL, c.magazineOr0());
        Ship s = new Ship(w.nextShipId(), owner, cls, "", at, 100, st, null, null, 0, "", 0, null, null, c.tankOr0(), c.crewOr0());
        List<Ship> ships = new ArrayList<>(w.ships());
        ships.add(s);
        return w.withShips(ships, s.id() + 1);
    }

    private static World order(World w, int country, Command cmd) {
        CommandResult r = new CommandExecutor(CFG).execute(w, country, cmd);
        assertThat(r.error()).as(r.error()).isNull();
        return r.world();
    }

    @Test
    void aPatrolWalksItsRouteAndRoundAgain() {
        World w = order(ship(world(false), 0, "destroyer", HARBOUR), 0, new Command.Mission(1, "patrol", List.of(A, B), 0, false));
        assertThat(w.ship(1).home()).isEqualTo(HARBOUR);
        List<Coord> visits = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            w = Update.run(w, CFG, 10 + i).next();
            Coord at = w.ship(1).at();
            if ((at.equals(A) || at.equals(B)) && (visits.isEmpty() || !visits.get(visits.size() - 1).equals(at))) visits.add(at);
        }
        assertThat(visits).as("A, B, A…").hasSizeGreaterThanOrEqualTo(3);
        assertThat(visits.get(0)).isEqualTo(A);
        assertThat(visits.get(1)).isEqualTo(B);
        assertThat(w.ship(1).mission()).isEqualTo("patrol");
    }

    @Test
    void lowOnShellsSheComesHomeRearmsAndGoesBackOut() {
        World w = order(ship(world(false), 0, "destroyer", A), 0, new Command.Mission(1, "blockade", List.of(A), 0, false));
        w = w.withShip(w.ship(1).withStock(w.ship(1).stock().with(SHELL, 5)));
        boolean wentHome = false, rearmed = false, backOnStation = false;
        for (int i = 0; i < 12 && !backOnStation; i++) {
            w = Update.run(w, CFG, 30 + i).next();
            Ship s = w.ship(1);
            if (s.at().equals(HARBOUR)) wentHome = true;
            if (wentHome && s.stock().get(SHELL) >= 60) rearmed = true;
            if (rearmed && s.at().equals(A)) backOnStation = true;
        }
        assertThat(wentHome).isTrue();
        assertThat(rearmed).isTrue();
        assertThat(backOnStation).as("she went back to her station by herself").isTrue();
    }

    @Test
    void aBlockadeStopsAnEnemyShipAtWarAndNobodyAtPeace() {
        Coord far = Hex.stepRaw(CAP, 0, 9);
        for (boolean war : new boolean[] {true, false}) {
            World w = order(ship(world(war), 0, "destroyer", A), 0, new Command.Mission(1, "blockade", List.of(A), 0, false));
            w = ship(w, 1, "cargo_ship", Hex.stepRaw(CAP, 0, 4));   // inside the blockade already, bound out past it
            w = w.withShip(w.ship(2).withDest(far));
            World n = Update.run(w, CFG, 50).next();
            Ship s = n.ship(2);
            if (war) {
                assertThat(s == null || !s.at().equals(far) && Hex.distanceRaw(s.at(), A) <= 1).as("held by the blockade").isTrue();
                if (s != null) assertThat(s.note()).contains("blockade");
            } else {
                assertThat(s.note()).as("at peace she sails on").doesNotContain("blockade");
                assertThat(Hex.distanceRaw(s.at(), Hex.stepRaw(CAP, 0, 4))).as("as far as her speed takes her").isGreaterThanOrEqualTo(2);
            }
        }
        // an order given now is stopped the same way
        World w = order(ship(world(true), 0, "destroyer", A), 0, new Command.Mission(1, "blockade", List.of(A), 0, false));
        w = ship(w, 1, "cargo_ship", Hex.stepRaw(CAP, 0, 3));
        w = w.withShip(w.ship(2).withMobility(10));
        CommandResult sail = new CommandExecutor(CFG).execute(w, 1, new Command.Sail(2, far));
        assertThat(sail.info()).contains("blockade");
        assertThat(Hex.distanceRaw(sail.world().ship(2).at(), A)).isLessThanOrEqualTo(1);
    }

    @Test
    void anEscortKeepsWithHerCharge() {
        Coord far = Hex.stepRaw(CAP, 0, 8);
        World w = ship(ship(world(false), 0, "destroyer", HARBOUR), 0, "cargo_ship", HARBOUR);
        w = order(w, 0, new Command.Mission(1, "escort", List.of(), 2, false));
        w = w.withShip(w.ship(2).withDest(far));
        for (int i = 0; i < 6; i++) w = Update.run(w, CFG, 60 + i).next();
        assertThat(w.ship(2).at()).isEqualTo(far);
        assertThat(Hex.distanceRaw(w.ship(1).at(), far)).as("she kept up").isLessThanOrEqualTo(1);
        assertThat(new CommandExecutor(CFG).execute(w, 0, new Command.Mission(1, "escort", List.of(), 1, false)).error()).contains("escort which");
    }

    @Test
    void aSearchWandersButStaysNearHome() {
        World w = order(ship(world(false), 0, "destroyer", HARBOUR), 0, new Command.Mission(1, "search", List.of(), 0, false));
        Set<Coord> been = new HashSet<>();
        int radius = CFG.units().ships().missionsOrDefault().searchRadiusOr0();
        for (int i = 0; i < 10; i++) {
            w = Update.run(w, CFG, 70 + i).next();
            been.add(w.ship(1).at());
            assertThat(Hex.distanceRaw(w.ship(1).at(), HARBOUR)).isLessThanOrEqualTo(radius);
        }
        assertThat(been).as("she went places").hasSizeGreaterThan(3);
    }

    @Test
    void anInterdictionShellsAnEnemyTrainAtWar() {
        Coord station = Hex.stepRaw(THEIR_CAP, 0, 2);
        for (boolean war : new boolean[] {true, false}) {
            World w = world(war);
            Sector depot = w.sector(THEIR_CAP);
            w = w.withSector(depot.withHeld(List.of(new HeldParcel(FOOD, 500, 1, THEIR_CAP, Hex.stepRaw(THEIR_CAP, 3, 1), 0, "rail"))));
            w = order(ship(w, 0, "destroyer", station), 0, new Command.Mission(1, "interdict", List.of(station), 0, false));
            World n = Update.run(w, CFG, 80).next();
            double left = n.sector(THEIR_CAP).held().stream().filter(HeldParcel::rail).mapToDouble(HeldParcel::qty).sum();
            if (war) assertThat(left).as("the train was shelled").isLessThan(500);
            else assertThat(left).isEqualTo(500);
        }
    }

    @Test
    void missionsAreForWarshipsAndNeedSenseInTheirOrders() {
        World w = ship(ship(world(false), 0, "cargo_ship", HARBOUR), 0, "destroyer", HARBOUR);
        CommandExecutor ex = new CommandExecutor(CFG);
        assertThat(ex.execute(w, 0, new Command.Mission(1, "patrol", List.of(A, B), 0, false)).error()).contains("no guns");
        assertThat(ex.execute(w, 0, new Command.Mission(2, "patrol", List.of(A), 0, false)).error()).contains("two points");
        assertThat(ex.execute(w, 0, new Command.Mission(2, "blockade", List.of(CAP), 0, false)).error()).contains("not sea");
        assertThat(ex.execute(w, 0, new Command.Mission(2, "search", List.of(), 0, true)).error()).contains("not on search");
        World on = order(w, 0, new Command.Mission(2, "search", List.of(), 0, false));
        assertThat(order(on, 0, new Command.Mission(2, "search", List.of(), 0, true)).ship(2).mission()).isNull();
    }
}
