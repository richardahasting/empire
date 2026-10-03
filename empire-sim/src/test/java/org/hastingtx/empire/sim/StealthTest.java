package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #71: stealth — KNOWN aircombat.c do_evade and ac_dog; plane.config sf and sb (stealth 80). */
class StealthTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord FIELD = Hex.stepRaw(CAP, 0, 2);
    private static final Coord THEIRS = Hex.stepRaw(CAP, 0, 3);
    private static final Coord THEIR_FIELD = Hex.stepRaw(CAP, 0, 4);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    private static World world(String bomber) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = w.withCountry(w.country(0).withCash(100000).withLevels(new Levels(400, 0, 0, 0)));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", THEIRS, 100000, 640, new Levels(400, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = TestWorlds.own(w, CFG, FIELD, "airfield", 100, 127, Map.of("civ", 300.0, "food", 500.0, "pet", 500.0, "shell", 500.0), Map.of());
        w = w.withSector(w.sector(THEIRS).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)).withOwner(1).withDesignation("agribusiness", 100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 400.0, "food", 900.0, "gun", 8.0)))));
        w = w.withSector(w.sector(THEIR_FIELD).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)).withOwner(1).withDesignation("airfield", 100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 300.0, "food", 500.0, "pet", 500.0)))));
        w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        w = w.withPlane(new Plane(1, 0, bomber, FIELD, 100, 400, 0, ""));
        return w.withPlane(new Plane(2, 1, "fighter_2", THEIR_FIELD, 100, 400, 0, ""));
    }

    private static int unseen(String bomber) {
        int n = 0;
        for (int u = 0; u < 20; u++) {
            CommandResult r = EX.execute(world(bomber).withUpdateNumber(u), 0, new Command.Bomb(1, THEIRS, false));
            assertThat(r.error()).as(r.error()).isNull();
            if (r.info().contains("unseen over")) n++;
        }
        return n;
    }

    @Test
    void aStealthBomberMostlySlipsPastAndAnOrdinaryOneNever() {
        assertThat(unseen("stealth_bomber")).as("stealth 80: about four raids in five").isBetween(10, 20);
        assertThat(unseen("bomber")).isZero();
    }

    @Test
    void unseenOverTheTargetMeansNoFlak() {
        for (int u = 0; u < 20; u++) {
            CommandResult r = EX.execute(world("stealth_bomber").withUpdateNumber(u), 0, new Command.Bomb(1, THEIRS, false));
            if (r.info().contains("unseen over " + THEIRS)) assertThat(r.info()).doesNotContain("flak over");
        }
    }
}
