package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #242. Richard, 2026-09-15: "Cargo ships should not be used as busses. Maybe we need a ferry boat?" Cargo ships
 * carry goods only; a ferry carries people between your harbours, and cannot land them anywhere else.
 */
class FerryTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord HARBOR = Hex.stepRaw(TestWorlds.CENTER, 0, 2);
    private static final Coord ISLAND = new Coord(18, 11);
    private static final Coord OFFSHORE = new Coord(17, 11);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    private static World world() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        w = TestWorlds.own(w, CFG, HARBOR, "harbor", 100, 127, Map.of("civ", 600.0, "mil", 500.0, "uw", 100.0, "food", 900.0, "lcm", 500.0, "pet", 2000.0), Map.of());
        return w.withSector(w.sector(ISLAND).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)));
    }

    private static World ship(World w, String cls, Coord at, Map<String, Double> aboard) {
        var c = CFG.units().ships().shipClass(cls);
        Ship s = new Ship(w.nextShipId(), 0, cls, "", at, 100, Stocks.of(COM.fromMap(aboard)), null, null, 0, "", 0, null, null, c.tankOr0(), c.crewOr0());
        List<Ship> ships = new ArrayList<>(w.ships());
        ships.add(s);
        return w.withShips(ships, s.id() + 1);
    }

    @Test
    void cargoShipsCarryGoodsButNotPeople() {
        for (String cls : List.of("cargo_ship", "super_cargo")) {
            World w = ship(world(), cls, HARBOR, Map.of());
            assertThat(EX.execute(w, 0, new Command.Load(1, "food", 100)).error()).as(cls + " still takes goods").isNull();
            for (String who : List.of("civ", "mil", "uw"))
                assertThat(EX.execute(w, 0, new Command.Load(1, who, 10)).error()).as(cls + " refuses " + who).contains("cannot carry " + who);
        }
    }

    @Test
    void aFerryCarriesPeopleUpToItsHoldAndNoGoods() {
        World w = ship(world(), "ferry", HARBOR, Map.of());
        CommandResult civ = EX.execute(w, 0, new Command.Load(1, "civ", 200));
        assertThat(civ.error()).as(civ.error()).isNull();
        CommandResult mil = EX.execute(civ.world(), 0, new Command.Load(1, "mil", 200));
        assertThat(mil.error()).as(mil.error()).isNull();
        assertThat(mil.world().ship(1).load()).as("a hold of 300").isEqualTo(300);
        assertThat(EX.execute(w, 0, new Command.Load(1, "uw", 50)).error()).isNull();
        assertThat(EX.execute(w, 0, new Command.Load(1, "food", 10)).error()).contains("cannot carry food");
        var cls = CFG.units().ships().shipClass("ferry");
        assertThat(CFG.units().ships().crewIsCivilian(cls)).as("a civilian crew, like any merchantman").isTrue();
    }

    @Test
    void peopleAlreadyAboardACargoShipCanStillGetOff() {
        World w = ship(world(), "cargo_ship", HARBOR, Map.of("civ", 150.0));
        CommandResult r = EX.execute(w, 0, new Command.Unload(1, "civ", 150));
        assertThat(r.error()).as("nobody is stranded aboard by the rule change").isNull();
        assertThat(r.world().ship(1).stock().get(COM.civ)).isZero();
    }

    @Test
    void aFerryCannotLandAnyoneOnForeignOrUnownedShore() {
        World w = ship(world(), "ferry", OFFSHORE, Map.of("civ", 100.0, "mil", 50.0));
        assertThat(EX.execute(w, 0, new Command.Land(1, ISLAND)).error()).contains("assault ship");
    }
}
