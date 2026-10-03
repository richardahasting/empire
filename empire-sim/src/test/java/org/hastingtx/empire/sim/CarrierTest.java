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

/** Issue #71: carriers as floating airfields — KNOWN plnsub.c pln_airbase_ok, carrier_planes, ship_can_carry; ship.config cal. */
class CarrierTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord FIELD = Hex.stepRaw(CAP, 0, 2);     // our airfield on the rim
    private static final Coord SEA = Hex.stepRaw(CAP, 0, 3);       // the carrier, just off it
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int PET = COM.index("pet"), SHELL = COM.index("shell");

    private static World world(double carrierEff) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = w.withCountry(w.country(0).withCash(100000).withLevels(new Levels(200, 0, 0, 0)));
        w = TestWorlds.own(w, CFG, FIELD, "airfield", 100, 127, Map.of("civ", 300.0, "food", 500.0, "pet", 100.0, "shell", 100.0), Map.of());
        var cc = CFG.units().ships().shipClass("carrier");
        Ship carrier = new Ship(1, 0, "carrier", "Hornet", SEA, carrierEff, Stocks.zero(COM.size()).with(PET, 200).with(SHELL, 50), null, null, 0, "", 100, null, null, cc.tankOr0(), cc.crewOr0());
        return w.withShips(List.of(carrier), 2);
    }

    private static World plane(World w, long id, String cls, Coord at) { return w.withPlane(new Plane(id, 0, cls, at, 100, 200, 0, "")); }

    private static World ok(World w, Command c) {
        CommandResult r = EX.execute(w, 0, c);
        assertThat(r.error()).as(r.error()).isNull();
        return r.world();
    }

    @Test
    void aLightPlaneLandsOnACarrierAndFliesFromHerHold() {
        World w = ok(plane(world(100), 1, "fighter_2", FIELD), new Command.Fly(List.of(1L), SEA, null, List.of()));
        assertThat(w.plane(1).ship()).isEqualTo(1);
        assertThat(w.plane(1).at()).isEqualTo(SEA);
        World naval = ok(plane(world(100), 2, "naval_plane", FIELD), new Command.Fly(List.of(2L), SEA, null, List.of()));
        double before = naval.ship(1).stock().get(PET);
        World flown = ok(naval, new Command.Recon(2, CAP));
        assertThat(flown.ship(1).stock().get(PET)).as("its petrol comes from her hold").isEqualTo(before - CFG.units().planes().planeClass("naval_plane").fuel());
        assertThat(flown.sector(FIELD).stock().get(PET)).as("not the field's").isEqualTo(100 - CFG.units().planes().planeClass("naval_plane").fuel());
    }

    @Test
    void notEveryPlaneNorEveryCarrier() {
        assertThat(EX.execute(plane(world(100), 1, "bomber", FIELD), 0, new Command.Fly(List.of(1L), SEA, null, List.of())).error()).as("too big").contains("nor is there a carrier");
        assertThat(EX.execute(plane(world(40), 1, "fighter_2", FIELD), 0, new Command.Fly(List.of(1L), SEA, null, List.of())).error()).as("under 50%").contains("nor is there a carrier");
        World full = world(100);
        for (long id = 10; id < 30; id++) full = full.withPlane(new Plane(id, 0, "fighter_2", SEA, 100, 200, 0, "", null, null, 0, 1));
        assertThat(EX.execute(plane(full, 1, "fighter_2", FIELD), 0, new Command.Fly(List.of(1L), SEA, null, List.of())).error()).as("twenty is her lot").contains("nor is there a carrier");
        World aboard = ok(plane(world(100), 1, "fighter_2", FIELD), new Command.Fly(List.of(1L), SEA, null, List.of()));
        World run = aboard.withShip(aboard.ship(1).withEfficiency(40));
        assertThat(EX.execute(run, 0, new Command.Recon(1, CAP)).error()).contains("works aircraft at 50");
    }

    @Test
    void sheTakesThemWithHer() {
        World w = ok(plane(world(100), 1, "fighter_2", FIELD), new Command.Fly(List.of(1L), SEA, null, List.of()));
        Coord on = Hex.stepRaw(CAP, 0, 5);
        w = w.withShip(w.ship(1).withDest(on));
        for (int i = 0; i < 3; i++) w = Update.run(w, CFG, 40 + i).next();
        assertThat(w.ship(1).at()).isNotEqualTo(SEA);
        assertThat(w.plane(1).at()).as("where she is").isEqualTo(w.ship(1).at());
        assertThat(EX.execute(w, 0, new Command.SetPrice("plane", List.of(1L), 100)).error()).contains("is aboard ship #1");
    }

    @Test
    void nothingFliesFromOrOntoACarrierForSale() {
        World aboard = ok(plane(world(100), 1, "fighter_2", FIELD), new Command.Fly(List.of(1L), SEA, null, List.of()));
        World listed = ok(aboard, new Command.SetPrice("ship", List.of(1L), 50000));
        assertThat(EX.execute(listed, 0, new Command.Recon(1, CAP)).error()).contains("is for sale; no aircraft fly from her");
        assertThat(EX.execute(listed, 0, new Command.Fly(List.of(1L), FIELD, null, List.of())).error()).contains("is for sale");
        World empty = ok(world(100), new Command.SetPrice("ship", List.of(1L), 50000));
        assertThat(EX.execute(plane(empty, 2, "fighter_2", FIELD), 0, new Command.Fly(List.of(2L), SEA, null, List.of())).error()).contains("nor is there a carrier");
    }

    @Test
    void flyingAshoreLeavesHer() {
        World w = ok(plane(world(100), 1, "fighter_2", FIELD), new Command.Fly(List.of(1L), SEA, null, List.of()));
        World back = ok(w, new Command.Fly(List.of(1L), FIELD, null, List.of()));
        assertThat(back.plane(1).ship()).isZero();
        assertThat(back.plane(1).at()).isEqualTo(FIELD);
        assertThat(back.ship(1).stock().get(PET)).as("the flight off her took her petrol").isEqualTo(200 - CFG.units().planes().planeClass("fighter_2").fuel());
    }
}
