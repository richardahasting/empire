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
 * Issue #247 (#71 slice 2): land units as the original had them (land.config, commands/buil.c, update/land.c, subs/attsub.c,
 * subs/takeover.c; Richard 2026-09-15: the original is the default).
 */
class LandUnitTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HQ = Hex.stepRaw(CAP, 1, 1);
    private static final Coord FRONT = Hex.stepRaw(CAP, 0, 2);
    private static final Coord TARGET = Hex.stepRaw(CAP, 0, 3);
    private static final Coord DEPTH = Hex.stepRaw(CAP, 0, 4);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    private static World world() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = w.withCountry(w.country(0).withCash(100000).withLevels(new Levels(200, 0, 0, 0)));
        w = TestWorlds.own(w, CFG, HQ, "headquarters", 100, 127, Map.of("civ", 800.0, "mil", 400.0, "food", 5000.0, "lcm", 200.0, "hcm", 200.0), Map.of());
        return w;
    }

    private static World unitAt(World w, Coord at, String cls, double eff, Map<String, Double> stock) {
        LandUnit u = new LandUnit(w.nextUnitId(), 0, cls, at, eff, Stocks.of(COM.fromMap(stock)), 100, 200, 0, "");
        return w.withUnit(u);
    }

    @Test
    void aUnitIsRaisedInAHeadquartersAtATenth() {
        CommandResult r = EX.execute(world(), 0, new Command.BuildUnit(HQ, "infantry"));
        assertThat(r.error()).as(r.error()).isNull();
        LandUnit u = r.world().units().get(0);
        assertThat(u.efficiency()).isEqualTo(10);
        assertThat(u.cls()).isEqualTo("infantry");
        assertThat(r.world().sector(HQ).stock().get(COM.index("lcm"))).as("a tenth of 10 lcm, rounded up").isEqualTo(199);
        assertThat(r.world().country(0).cash()).as("a tenth of $500").isEqualTo(100000 - 50);

        assertThat(EX.execute(world(), 0, new Command.BuildUnit(CAP, "infantry")).error()).contains("headquarters");
        assertThat(EX.execute(world(), 0, new Command.BuildUnit(HQ, "zeppelin")).error()).contains("unknown land unit class");
        World low = world().withCountry(world().country(0).withLevels(new Levels(10, 0, 0, 0)));
        assertThat(EX.execute(low, 0, new Command.BuildUnit(HQ, "infantry")).error()).contains("needs tech 50");
    }

    @Test
    void aUnitTakesOnSoldiersUpToWhatItCarries() {
        World w = unitAt(world(), HQ, "infantry", 100, Map.of());
        CommandResult r = EX.execute(w, 0, new Command.LoadUnit(1, "mil", 500, false));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().unit(1).stock().get(COM.mil)).as("infantry carries 100 mil").isEqualTo(100);
        assertThat(r.world().sector(HQ).stock().get(COM.mil)).isEqualTo(300);
        assertThat(EX.execute(r.world(), 0, new Command.LoadUnit(1, "lcm", 5, false)).error()).contains("does not carry lcm");
        CommandResult down = EX.execute(r.world(), 0, new Command.LoadUnit(1, "mil", 40, true));
        assertThat(down.world().unit(1).stock().get(COM.mil)).isEqualTo(60);
    }

    @Test
    void anUpdateBuildsItUpPaysItFeedsItAndGivesItMobility() {
        World w = unitAt(world(), HQ, "infantry", 10, Map.of("mil", 100.0, "food", 24.0));
        w = w.withUnit(w.unit(1).withMobility(0));
        World after = Update.run(w, CFG, 3).next();
        LandUnit u = after.unit(1);
        assertThat(u.efficiency()).as("built up in its headquarters").isGreaterThan(10);
        assertThat(u.mobility()).as("mobility accrues").isGreaterThan(0);
        assertThat(u.stock().get(COM.food)).as("the men ate").isLessThan(24);

        World hungry = unitAt(world(), HQ, "infantry", 100, Map.of("mil", 100.0));
        assertThat(Update.run(hungry, CFG, 4).next().unit(1).stock().get(COM.mil)).as("no rations: men starve").isLessThan(100);
    }

    @Test
    void aUnitMarchesThroughYourLandOnItsOwnMobility() {
        World w = unitAt(world(), HQ, "infantry", 100, Map.of("mil", 100.0));
        CommandResult r = EX.execute(w, 0, new Command.March(1, CAP));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().unit(1).at()).isEqualTo(CAP);
        assertThat(r.world().unit(1).mobility()).isLessThan(100);
        assertThat(EX.execute(w, 0, new Command.March(1, new Coord(20, 20))).error()).contains("sea");
    }

    /** Us beside Them at war; Them holding TARGET with a small garrison. */
    private static World war(double targetMil) {
        World w = world();
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", DEPTH, 100000, 640, new Levels(200, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        for (Coord at : new Coord[] {TARGET, DEPTH}) w = w.withSector(w.sector(at).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)));
        w = w.withSector(w.sector(TARGET).withOwner(1).withDesignation("agribusiness", 100).withStock(Stocks.of(COM.fromMap(Map.of("civ", 300.0, "mil", targetMil, "food", 500.0)))));
        w = w.withSector(w.sector(DEPTH).withOwner(1).withDesignation("agribusiness", 100));
        w = TestWorlds.own(w, CFG, FRONT, "agribusiness", 100, 127, Map.of("civ", 300.0, "mil", 200.0, "food", 500.0), Map.of());
        return w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
    }

    @Test
    void infantryInTheSectorMakesItHold() {
        Command.Attack attack = new Command.Attack(TARGET, List.of(new Command.Attack.Party(FRONT, 60)));
        assertThat(EX.execute(war(20), 0, attack).world().sector(TARGET).owner()).as("sixty against twenty").isZero();
        World held = war(20);
        held = held.withUnit(new LandUnit(held.nextUnitId(), 1, "infantry", TARGET, 100, Stocks.of(COM.fromMap(Map.of("mil", 100.0))), 50, 200, 0, ""));
        CommandResult r = EX.execute(held, 0, attack);
        assertThat(r.world().sector(TARGET).owner()).as("with a hundred infantry at defence 1.5 beside them").isEqualTo(1);
        assertThat(r.world().unit(1).stock().get(COM.mil)).as("the infantry took losses").isLessThan(100);
    }

    @Test
    void aUnitJoinsTheAttackAndMovesIn() {
        World w = war(40);
        w = w.withUnit(new LandUnit(w.nextUnitId(), 0, "infantry", FRONT, 100, Stocks.of(COM.fromMap(Map.of("mil", 100.0))), 127, 200, 0, ""));
        CommandResult r = EX.execute(w, 0, new Command.Attack(TARGET, List.of(), List.of(1L)));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().sector(TARGET).owner()).isZero();
        assertThat(r.world().unit(1).at()).as("moved in").isEqualTo(TARGET);
        assertThat(r.world().unit(1).mobility()).as("paid its way in").isLessThan(127);
        assertThat(r.info()).contains("units #1 moved in");
    }

    @Test
    void theLosersUnitsInATakenSectorAreCapturedOrBlownUp() {
        World w = war(5);
        w = w.withUnit(new LandUnit(w.nextUnitId(), 1, "supply", TARGET, 100, Stocks.of(COM.fromMap(Map.of("food", 100.0))), 50, 200, 0, ""));
        CommandResult r = EX.execute(w, 0, new Command.Attack(TARGET, List.of(new Command.Attack.Party(FRONT, 100))));
        assertThat(r.world().sector(TARGET).owner()).isZero();
        LandUnit u = r.world().unit(1);
        assertThat(u == null || u.owner() == 0).as("taken or destroyed, never still theirs").isTrue();
        if (u != null) assertThat(u.efficiency()).isLessThan(100);
    }

    /** KNOWN guerrilla(): security troops raid first and add three times their strength against che; infantry of the same size do not. */
    @Test
    void securityTroopsRaidTheGuerrillas() {
        World w = world();
        w = w.withSector(w.sector(HQ).withUnrest(20, 100, Sector.NOBODY, 60, 0).withStock(w.sector(HQ).stock().with(COM.mil, 0)));
        int security = 0, infantry = 0;
        for (long seed = 1; seed <= 10; seed++) {
            security += Update.run(unitAt(w, HQ, "security", 100, Map.of("mil", 50.0, "food", 30.0)), CFG, seed).next().sector(HQ).che();
            infantry += Update.run(unitAt(w, HQ, "infantry", 100, Map.of("mil", 50.0, "food", 30.0)), CFG, seed).next().sector(HQ).che();
        }
        assertThat(security).as("guerrillas left over ten updates: security " + security + " vs infantry " + infantry).isLessThan(infantry);
    }
}
