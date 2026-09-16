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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #252 (#71 slice 2b): ships carry land units, as the original did (ship.config nla, land.config's light flag,
 * subs/attsub.c A_ASSAULT). A light unit goes aboard in one of your harbours, sails with her, storms the beach with her
 * landing party, and goes down with her if she sinks.
 */
class UnitsAboardTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord PORT = Hex.stepRaw(CAP, 0, 2);       // our harbour on the rim of the disc
    private static final Coord NEAR_SEA = Hex.stepRaw(CAP, 0, 3);
    private static final Coord COAST = new Coord(18, 11);          // their coastal sector
    private static final Coord INLAND = new Coord(19, 11);
    private static final Coord OFFSHORE = new Coord(17, 11);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    /** Us with a harbour at PORT and an assault ship there; a unit of the named class standing in the harbour. */
    private static World world(String cls, String shipCls, Coord shipAt) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = TestWorlds.own(w, CFG, PORT, "harbor", 100, 127, Map.of("civ", 500.0, "mil", 400.0, "food", 5000.0), Map.of());
        var sc = CFG.units().ships().shipClass(shipCls);
        Ship s = new Ship(1, 0, shipCls, "", shipAt, 100, Stocks.zero(COM.size()).with(COM.mil, 100), null, null, 0, "", 0, null, null, sc.tankOr0(), sc.crewOr0());
        w = w.withShips(List.of(s), 2);
        LandUnit u = new LandUnit(w.nextUnitId(), 0, cls, PORT, 100, Stocks.of(COM.fromMap(Map.of("mil", 100.0, "food", 50.0))), 100, 200, 0, "", 0);
        return w.withUnit(u);
    }

    @Test
    void aLightUnitGoesAboardInYourHarbourAndAshoreAgain() {
        World w = world("infantry", "assault_ship", PORT);
        CommandResult r = EX.execute(w, 0, new Command.Board(1, 1));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().unit(1).ship()).isEqualTo(1);
        assertThat(r.world().unit(1).aboard()).isTrue();
        assertThat(r.info()).contains("1/6");

        CommandResult back = EX.execute(r.world(), 0, new Command.Board(1, 0));
        assertThat(back.error()).as(back.error()).isNull();
        assertThat(back.world().unit(1).ship()).isZero();
        assertThat(back.world().unit(1).at()).isEqualTo(PORT);
        assertThat(EX.execute(w, 0, new Command.Board(1, 0)).error()).contains("already ashore");
    }

    /** Every class we have today is light, so the weight rule waits for a heavy one; the rest bite now. */
    @Test
    void onlyIntoAShipThatCarriesUnitsAndOnlyBesideIt() {
        assertThat(EX.execute(world("infantry", "fishing_boat", PORT), 0, new Command.Board(1, 1)).error()).contains("carries no land units");
        assertThat(EX.execute(world("infantry", "assault_ship", OFFSHORE), 0, new Command.Board(1, 1)).error()).contains("at " + OFFSHORE);
    }

    @Test
    void sheCarriesNoMoreThanHerBerths() {
        World w = world("infantry", "ferry", PORT);      // a ferry takes two
        for (int i = 0; i < 2; i++) w = w.withUnit(new LandUnit(w.nextUnitId(), 0, "infantry", PORT, 100, Stocks.zero(COM.size()), 100, 200, 0, "", 0));
        assertThat(EX.execute(w, 0, new Command.Board(1, 1)).error()).isNull();
        w = EX.execute(w, 0, new Command.Board(1, 1)).world();
        w = EX.execute(w, 0, new Command.Board(2, 1)).world();
        assertThat(EX.execute(w, 0, new Command.Board(3, 1)).error()).contains("no more than 2");
    }

    @Test
    void sheCarriesItWhereverSheSails() {
        World w = EX.execute(world("infantry", "assault_ship", PORT), 0, new Command.Board(1, 1)).world();
        w = EX.execute(w, 0, new Command.Sail(1, NEAR_SEA)).world();
        World after = Update.run(w, CFG, 7).next();
        assertThat(after.ship(1).at()).as("she moved").isNotEqualTo(PORT);
        assertThat(after.unit(1).at()).as("it rode with her").isEqualTo(after.ship(1).at());
        assertThat(after.unit(1).ship()).isEqualTo(1);
    }

    @Test
    void aUnitAboardCannotMarchAndSheCannotBeScrapped() {
        World w = EX.execute(world("infantry", "assault_ship", PORT), 0, new Command.Board(1, 1)).world();
        assertThat(EX.execute(w, 0, new Command.March(1, CAP)).error()).contains("aboard");
        assertThat(EX.execute(w, 0, new Command.Scrap(1)).error()).contains("aboard");
    }

    /** Us offshore of their coast, at war, with an assault ship and a unit of the named class aboard her. */
    private static World invasion(double coastMil) { return invasion(coastMil, "infantry"); }

    private static World invasion(double coastMil, String cls) {
        World w = world(cls, "assault_ship", OFFSHORE);
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", INLAND, 100000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        for (Coord at : new Coord[] {COAST, INLAND}) w = w.withSector(w.sector(at).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)));
        w = w.withSector(w.sector(COAST).withOwner(1).withDesignation("agribusiness", 100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 300.0, "mil", coastMil, "food", 500.0)))));
        w = w.withSector(w.sector(INLAND).withOwner(1).withDesignation("agribusiness", 100));
        w = w.withUnit(w.unit(1).withAt(OFFSHORE).withShip(1));
        return w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
    }

    @Test
    void theUnitAboardStormsTheBeachAndHoldsTheSectorItTakes() {
        CommandResult r = EX.execute(invasion(20), 0, new Command.Land(1, COAST));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().sector(COAST).owner()).as("taken").isZero();
        LandUnit u = r.world().unit(1);
        assertThat(u.ship()).as("it is ashore now").isZero();
        assertThat(u.at()).isEqualTo(COAST);
        assertThat(u.stock().get(COM.mil)).as("its share of the survivors").isBetween(1.0, 100.0);
        assertThat(r.info()).contains("#1");
    }

    /** KNOWN attsub.c: a unit without the assault training rides the landing out and is still aboard after. */
    @Test
    void onlyAnAssaultUnitStormsTheBeach() {
        CommandResult r = EX.execute(invasion(20, "supply"), 0, new Command.Land(1, COAST));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().sector(COAST).owner()).as("the ship's own party took it").isZero();
        assertThat(r.world().unit(1).ship()).as("the supply column stayed aboard").isEqualTo(1);
    }

    /** Its hundred men are half the landing party, so the assault carries a garrison it could not have carried alone. */
    @Test
    void itsMenCountInTheFight() {
        assertThat(EX.execute(invasion(140), 0, new Command.Land(1, COAST)).world().sector(COAST).owner())
                .as("100 aboard and 100 in the unit against 140").isZero();
        World alone = invasion(140);
        alone = alone.withoutUnit(1);
        assertThat(EX.execute(alone, 0, new Command.Land(1, COAST)).world().sector(COAST).owner())
                .as("100 alone against 140 is not enough").isEqualTo(1);
    }

    /** Their destroyer against our transport at war: when she goes down, the unit she carried goes with her. */
    @Test
    void theUnitGoesDownWithHer() {
        World w = invasion(20);
        var dc = CFG.units().ships().shipClass("destroyer");
        Ship d = new Ship(2, 1, "destroyer", "", OFFSHORE, 100,
                Stocks.zero(COM.size()).with(COM.index("gun"), dc.gunsOr0()).with(COM.index("shell"), dc.magazineOr0()),
                null, null, 0, "", 0, null, null, dc.tankOr0(), dc.crewOr0());
        List<Ship> ships = new ArrayList<>(w.ships());
        ships.add(d);
        w = w.withShips(ships, 3);
        boolean sunk = false;
        for (int i = 0; i < 12 && !sunk; i++) { w = Update.run(w, CFG, 300 + i).next(); sunk = w.ship(1) == null; }
        assertThat(sunk).as("our transport went down").isTrue();
        assertThat(w.unit(1)).as("the unit she carried went with her").isNull();
    }
}
