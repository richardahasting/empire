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
 * Issue #206. Richard, 2026-09-14: enemy-held coast is combat. At war only; man for man until one side
 * is gone; the defender's neighbours fight too; the winner takes the sector with its people and most of
 * its stock.
 */
class AssaultTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord COAST = new Coord(18, 11);          // their coastal sector
    private static final Coord INLAND = new Coord(19, 11);         // their sector next to it
    private static final Coord OFFSHORE = new Coord(17, 11);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    /** Us at the centre; them holding COAST (and INLAND) with the given garrisons; at war or not. */
    private static World world(boolean war, String coastType, double coastMil, double inlandMil) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", INLAND, 100000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        for (Coord at : new Coord[] {COAST, INLAND}) w = w.withSector(w.sector(at).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)));
        w = w.withSector(w.sector(COAST).withOwner(1).withDesignation(coastType, 100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 300.0, "mil", coastMil, "food", 500.0, "lcm", 200.0)))).withDistCenter(INLAND).withRoadLevel(80));
        w = w.withSector(w.sector(INLAND).withOwner(1).withDesignation("agribusiness", 100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 300.0, "mil", inlandMil)))));
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        var c = CFG.units().ships().shipClass("assault_ship");
        Ship s = new Ship(1, 0, "assault_ship", "", OFFSHORE, 100, Stocks.zero(COM.size()).with(COM.mil, 100).with(COM.civ, 20), null, null, 0, "", 0, null, null, c.tankOr0(), c.crewOr0());
        return w.withShips(List.of(s), 2);
    }

    @Test
    void onlyAtWar() {
        CommandResult r = EX.execute(world(false, "agribusiness", 10, 0), 0, new Command.Land(1, COAST));
        assertThat(r.error()).contains("only at war");
    }

    @Test
    void aWeakGarrisonIsOverwhelmedAndTheSectorTaken() {
        CommandResult r = EX.execute(world(true, "agribusiness", 10, 0), 0, new Command.Land(1, COAST));
        assertThat(r.error()).as(r.error()).isNull();
        Sector s = r.world().sector(COAST);
        assertThat(s.owner()).as("taken").isEqualTo(0);
        assertThat(s.designation()).isEqualTo("agribusiness");
        assertThat(s.stock().get(COM.mil)).as("survivors garrison it").isBetween(1.0, 100.0);
        assertThat(s.stock().get(COM.civ)).as("its people, and ours who followed").isEqualTo(320);
        assertThat(s.stock().get(COM.food)).as("a tenth of the goods lost in the fighting").isEqualTo(450);
        assertThat(s.distCenter()).as("no longer wired to their network").isNull();
        assertThat(s.roadLevel()).isLessThan(80);
        assertThat(r.world().ship(1).load()).isZero();
        assertThat(r.info()).contains("taken");
        // and it holds through an update: the books balance
        assertThat(Update.run(r.world(), CFG, 7).next().sector(COAST).owner()).isEqualTo(0);
    }

    @Test
    void aFortifiedGarrisonThrowsThemBackIntoTheSea() {
        CommandResult r = EX.execute(world(true, "fortress", 150, 0), 0, new Command.Land(1, COAST));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().sector(COAST).owner()).as("still theirs").isEqualTo(1);
        assertThat(r.world().ship(1).stock().get(COM.mil)).as("every soldier lost").isZero();
        assertThat(r.world().ship(1).stock().get(COM.civ)).as("the civilians never went ashore").isEqualTo(20);
        assertThat(r.world().sector(COAST).stock().get(COM.mil)).as("but the defenders bled").isLessThan(150);
        assertThat(r.info()).contains("thrown back");
    }

    @Test
    void theNeighboursFightToo() {
        // alone, forty in the sector fall to a hundred; with three hundred next door, the landing fails
        CommandResult alone = EX.execute(world(true, "agribusiness", 40, 0), 0, new Command.Land(1, COAST));
        assertThat(alone.world().sector(COAST).owner()).isEqualTo(0);
        World held = world(true, "agribusiness", 40, 300);
        CommandResult backed = EX.execute(held, 0, new Command.Land(1, COAST));
        assertThat(backed.world().sector(COAST).owner()).as("held in depth").isEqualTo(1);
        assertThat(backed.world().sector(INLAND).stock().get(COM.mil)).as("the neighbours took losses").isLessThan(300);
        assertThat(backed.info()).contains("from next door");
    }

    @Test
    void theSameAssaultGoesTheSameWay() {
        World w = world(true, "agribusiness", 70, 30);
        CommandResult a = EX.execute(w, 0, new Command.Land(1, COAST)), b = EX.execute(w, 0, new Command.Land(1, COAST));
        assertThat(a.info()).isEqualTo(b.info());
    }
}
