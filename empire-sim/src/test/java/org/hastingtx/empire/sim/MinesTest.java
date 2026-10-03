package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.command.Mines;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.view.CountryView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #71: mines — KNOWN mine.c, shpsub.c, lndsub.c, attsub.c get_mine_dsupport, plnsub.c pln_mine and pln_sweep. */
class MinesTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOUR = Hex.stepRaw(CAP, 0, 2);
    private static final Coord SEA1 = Hex.stepRaw(CAP, 0, 3), SEA2 = Hex.stepRaw(CAP, 0, 4), SEA3 = Hex.stepRaw(CAP, 0, 5);
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int SHELL = COM.index("shell");

    private static World world() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0, "shell", 200.0));
        w = w.withCountry(w.country(0).withCash(100000).withLevels(new Levels(200, 0, 0, 0)));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", new Coord(2, 2), 100000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        return TestWorlds.own(w, CFG, HARBOUR, "harbor", 100, 127, Map.of("civ", 300.0, "food", 500.0, "pet", 1000.0), Map.of());
    }

    private static World ship(World w, long id, String cls, Coord at, double eff) {
        var c = CFG.units().ships().shipClass(cls);
        return w.withShip(new Ship(id, 0, cls, "", at, eff, Stocks.zero(COM.size()).with(SHELL, 40), null, null, 0, "", 100, null, null, c.tankOr0(), c.crewOr0()));
    }

    private static World ok(World w, int who, Command c) {
        CommandResult r = EX.execute(w, who, c);
        assertThat(r.error()).as(r.error()).isNull();
        return r.world();
    }

    @Test
    void aMinelayerLaysFromHerShellsAtSeaOnly() {
        World w = ok(ship(world(), 1, "destroyer", SEA1, 100), 0, new Command.Lay(1, 10));
        assertThat(w.sector(SEA1).mines()).isEqualTo(10);
        assertThat(w.ship(1).stock().get(SHELL)).isEqualTo(30);
        assertThat(EX.execute(ship(world(), 1, "destroyer", HARBOUR, 100), 0, new Command.Lay(1, 5)).error()).contains("in harbour");
        assertThat(EX.execute(ship(world(), 1, "cargo_ship", SEA1, 100), 0, new Command.Lay(1, 5)).error()).contains("does not lay mines");
        assertThat(ok(ship(world(), 1, "destroyer", SEA1, 100), 0, new Command.Lay(1, 99)).sector(SEA1).mines()).as("all the shells she had").isEqualTo(40);
    }

    @Test
    void aShipStrikesAMineAndStopsThere() {
        World w = ship(world(), 1, "destroyer", HARBOUR, 100).withSector(world().sector(SEA2).withMines(5000));
        w = w.withShip(w.ship(1).withDest(SEA3));
        for (int i = 0; i < 3 && w.ship(1) != null && !w.ship(1).at().equals(SEA2); i++) w = Update.run(w, CFG, 10 + i).next();
        assertThat(w.ship(1).at()).as("stopped where she struck it").isEqualTo(SEA2);
        assertThat(w.ship(1).efficiency()).isLessThan(100);
        assertThat(w.sector(SEA2).mines()).isEqualTo(4999);
    }

    @Test
    void aMineCanSinkAWornHullWithAllAboard() {
        World w = ship(world(), 1, "cargo_ship", SEA1, 21).withSector(world().sector(SEA2).withMines(5000));
        w = w.withShip(w.ship(1).withStock(w.ship(1).stock().with(COM.food, 100)).withDest(SEA3));
        World after = Update.run(w, CFG, 20).next();   // conservation is checked: what she carried is tallied as lost
        assertThat(after.ship(1)).as("sunk").isNull();
    }

    @Test
    void aShipSailedByHandRunsTheGauntletToo() {
        World w = ship(world(), 1, "destroyer", SEA1, 100).withSector(world().sector(SEA2).withMines(5000));
        w = w.withShip(w.ship(1).withMobility(30));
        CommandResult r = EX.execute(w, 0, new Command.Sail(1, SEA3));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).contains("struck a mine at");
        assertThat(r.world().ship(1).at()).isEqualTo(SEA2);
    }

    @Test
    void aMinesweeperClearsTheWayAndGetsHerShellsBack() {
        World w = ship(world(), 1, "minesweeper", HARBOUR, 100).withSector(world().sector(SEA1).withMines(2));
        w = w.withShip(w.ship(1).withStock(w.ship(1).stock().with(SHELL, 0)).withDest(SEA2));
        World after = Update.run(w, CFG, 30).next();
        assertThat(after.sector(SEA1).mines()).as("five tries at two in three").isLessThan(2);
        assertThat(after.ship(1).stock().get(SHELL)).as("a shell for each swept").isGreaterThan(0);
    }

    @Test
    void anEngineerLaysLandMinesThatOnlyItsOwnersSee() {
        World w = world().withUnit(new LandUnit(1, 0, "engineer", CAP, 100, Stocks.zero(COM.size()).with(SHELL, 3).with(COM.mil, 10), 20, 200, 0, ""));
        World r = ok(w, 0, new Command.LandMine(1, 10));
        assertThat(r.sector(CAP).mines()).isEqualTo(10);
        assertThat(r.unit(1).mobility()).isEqualTo(10);
        assertThat(r.unit(1).stock().get(SHELL) + r.sector(CAP).stock().get(SHELL)).as("ten shells gone").isEqualTo(3 + 200 - 10);
        assertThat(CountryView.of(r, CFG, 0).sectors().stream().filter(s -> s.at().equals(CAP)).findFirst().orElseThrow().mines()).isEqualTo(10);
        World inf = world().withUnit(new LandUnit(2, 0, "infantry", CAP, 100, Stocks.zero(COM.size()).with(COM.mil, 10), 20, 200, 0, ""));
        assertThat(EX.execute(inf, 0, new Command.LandMine(2, 5)).error()).contains("only engineers");
    }

    @Test
    void aUnitStrikesTheOldOwnersMinesInLandTakenFromThem() {
        Coord taken = Hex.stepRaw(CAP, 0, 1);
        World w = world().withSector(world().sector(taken).withOwner(0).withDesignation("agribusiness", 100).withUnrest(0, 100, 1, 0, Sector.NOBODY).withMines(5000));
        w = w.withUnit(new LandUnit(1, 0, "infantry", CAP, 100, Stocks.zero(COM.size()).with(COM.mil, 10), 127, 200, 0, ""));
        CommandResult r = EX.execute(w, 0, new Command.March(1, Hex.stepRaw(CAP, 0, 2)));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).contains("struck a mine at");
        assertThat(r.world().unit(1) == null || r.world().unit(1).at().equals(taken)).isTrue();
        World ours = w.withSector(w.sector(taken).withUnrest(0, 100, Sector.NOBODY, 0, Sector.NOBODY));
        assertThat(EX.execute(ours, 0, new Command.March(1, Hex.stepRaw(CAP, 0, 2))).info()).as("its own walk through").doesNotContain("struck");
    }

    @Test
    void landMinesStiffenTheDefence() {
        Sector theirs = world().sector(Hex.stepRaw(CAP, 0, 1)).withOwner(1).withDesignation("agribusiness", 100).withMines(50);
        assertThat(Mines.defence(CFG, theirs, 0, false)).as("20 count, 2% each").isEqualTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Mines.defence(CFG, theirs, 0, true)).as("halved against engineers").isEqualTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Mines.defence(CFG, theirs, 1, false)).as("not against their owner").isZero();
    }

    @Test
    void navalPlanesDropMinesOnTheSeaAndSweepThem() {
        Coord field = Hex.stepRaw(CAP, 1, 1);
        World w = TestWorlds.own(world(), CFG, field, "airfield", 100, 127, Map.of("civ", 100.0, "food", 100.0, "pet", 100.0, "shell", 100.0), Map.of());
        w = w.withPlane(new Plane(1, 0, "naval_plane", field, 100, 200, 0, ""));
        World dropped = ok(w, 0, new Command.Drop(List.of(1L), SEA1, "shell", List.of()));
        int laid = dropped.sector(SEA1).mines();
        assertThat(laid).isGreaterThan(0);
        assertThat(dropped.sector(field).stock().get(SHELL)).isEqualTo(100 - laid);
        int left = laid;
        for (int u = 0; u < 20 && left == laid; u++) left = ok(dropped.withUpdateNumber(u), 0, new Command.SweepAir(List.of(1L), SEA1, List.of())).sector(SEA1).mines();
        assertThat(left).as("a sweep clears one now and then").isLessThan(laid);
    }

    /**
     * Issue #312: the far end of a sweep need not be water. {@code lift} only asks that the hex be in bounds, and the
     * sweep clears every sea hex along the flight path — so a run aimed at a coast, to clear its approaches, is a legal
     * order. The web client refused it before the server ever saw it; this pins the engine's side of that.
     */
    @Test
    void aSweepMayBeAimedAtLand() {
        Coord field = Hex.stepRaw(CAP, 1, 1);
        World w = TestWorlds.own(world(), CFG, field, "airfield", 100, 127, Map.of("civ", 100.0, "food", 100.0, "pet", 100.0, "shell", 100.0), Map.of());
        w = w.withPlane(new Plane(1, 0, "naval_plane", field, 100, 200, 0, ""));
        assertThat(w.sector(HARBOUR).isLand()).as("the target really is dry land").isTrue();
        assertThat(EX.execute(w, 0, new Command.SweepAir(List.of(1L), HARBOUR, List.of())).error()).isNull();
    }
}
