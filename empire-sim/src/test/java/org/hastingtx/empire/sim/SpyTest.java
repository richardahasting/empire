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

/**
 * Issue #254 (#71 slice 2c): spies. The original's (subs/lndsub.c, commands/sabo.c, subs/landgun.c): a spy is the one
 * unit that may stand in another country's sector, it is worth nothing in a fight, and one chance in ten at full
 * efficiency has it caught wherever it goes. `incite` is Richard's, 2026-09-15.
 */
class SpyTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord OURS = Hex.stepRaw(CAP, 0, 2);       // our sector on the rim
    private static final Coord THEIRS = Hex.stepRaw(CAP, 0, 3);     // theirs, next to it
    private static final Coord DEEPER = Hex.stepRaw(CAP, 0, 4);
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int SHELL = COM.index("shell"), PET = COM.index("pet");

    /** Us on the disc, Them holding the two sectors beyond our rim, at war or not; our spy standing where named. */
    private static World world(boolean war, String cls, Coord spyAt, Map<String, Double> theirStock) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", DEEPER, 100000, 640, new Levels(200, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        for (Coord at : new Coord[] {THEIRS, DEEPER}) {
            w = w.withSector(w.sector(at).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0))
                    .withOwner(1).withDesignation("agribusiness", 100).withMobility(100)
                    .withStock(Stocks.of(COM.fromMap(theirStock))));
        }
        w = w.withCountry(w.country(0).withLevels(new Levels(200, 0, 0, 0)));
        w = TestWorlds.own(w, CFG, OURS, "agribusiness", 100, 127, Map.of("civ", 300.0, "food", 500.0), Map.of());
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        LandUnit u = new LandUnit(w.nextUnitId(), 0, cls, spyAt, 100,
                Stocks.of(COM.fromMap(Map.of("mil", 1.0, "shell", 5.0, "food", 6.0))), 127, 200, 0, "", 0);
        return w.withUnit(u);
    }

    private static World world(String cls, Coord spyAt) {
        return world(true, cls, spyAt, Map.of("civ", 400.0, "food", 500.0));
    }

    @Test
    void aSpyWalksIntoTheirLandAndNothingElseDoes() {
        CommandResult spy = EX.execute(world("infiltrator", OURS), 0, new Command.March(1, THEIRS));
        assertThat(spy.error()).as(spy.error()).isNull();
        assertThat(spy.world().unit(1) == null || spy.world().unit(1).at().equals(THEIRS))
                .as("it got there, or was caught trying").isTrue();

        assertThat(EX.execute(world("infantry", OURS), 0, new Command.March(1, THEIRS)).error())
                .as("anything else is kidnapped in their land, so it is refused").contains("not yours");
    }

    /** KNOWN LND_SPY_DETECT_CHANCE: one chance in ten at full efficiency, and much worse when it is battered. */
    @Test
    void aWornSpyIsCaughtFarMoreOften() {
        int fit = 0, worn = 0;
        for (long seed = 1; seed <= 60; seed++) {
            GameConfig cfg = TestWorlds.teaching(seed);
            CommandExecutor ex = new CommandExecutor(cfg);
            World w = world("infiltrator", OURS);
            if (ex.execute(w, 0, new Command.March(1, THEIRS)).world().unit(1) == null) fit++;
            World hurt = w.withUnit(w.unit(1).withEfficiency(30));
            if (ex.execute(hurt, 0, new Command.March(1, THEIRS)).world().unit(1) == null) worn++;
        }
        assertThat(fit).as("a 10% chance over sixty tries: " + fit).isLessThan(20);
        assertThat(worn).as("an 80% chance over sixty tries: " + worn).isGreaterThan(fit);
    }

    @Test
    void aSpyIsWorthNothingInAFightAndIsRefusedAnAttack() {
        World w = world("commando", THEIRS);
        assertThat(EX.execute(w, 0, new Command.Attack(DEEPER, List.of(), List.of(1L))).error())
                .contains("is a spy");
    }

    @Test
    void sabotageBlowsUpWhatTheSectorWasKeeping() {
        World w = world(true, "commando", THEIRS, Map.of("civ", 400.0, "food", 500.0, "shell", 200.0, "pet", 400.0));
        double before = w.sector(THEIRS).efficiency(), foodBefore = w.sector(THEIRS).stock().get(COM.food);
        CommandResult r = EX.execute(w, 0, new Command.Sabotage(1));
        assertThat(r.error()).as(r.error()).isNull();
        Sector s = r.world().sector(THEIRS);
        assertThat(s.efficiency()).as("the sector is wrecked").isLessThan(before);
        assertThat(s.stock().get(COM.food)).as("and so is what it held").isLessThan(foodBefore);
        if (r.world().unit(1) != null) assertThat(r.world().unit(1).stock().get(SHELL)).as("a shell was spent").isEqualTo(4);
        assertThat(r.info()).contains("charge went off");
    }

    @Test
    void sabotageNeedsAShellASpyAndTheirGround() {
        World noShells = world("commando", THEIRS);
        noShells = noShells.withUnit(noShells.unit(1).withStock(noShells.unit(1).stock().with(SHELL, 0)));
        assertThat(EX.execute(noShells, 0, new Command.Sabotage(1)).error()).contains("no shells");
        assertThat(EX.execute(world("infantry", THEIRS), 0, new Command.Sabotage(1)).error()).contains("not a spy");
        assertThat(EX.execute(world("commando", OURS), 0, new Command.Sabotage(1)).error()).contains("your own sector");
    }

    /** NEW (Richard 2026-09-15): the spy who goes in to make trouble. */
    @Test
    void incitingMakesThemDisloyalAndThenArmsThem() {
        World w = world("infiltrator", THEIRS);
        var inc = CFG.units().land().spy().incite();
        int loyalty = 0;
        boolean armed = false;
        for (int i = 0; i < 12 && !armed; i++) {
            CommandResult r = EX.execute(w, 0, new Command.Incite(1));
            assertThat(r.error()).as(r.error()).isNull();
            if (r.world().unit(1) == null) { w = r.world().withUnit(w.unit(1)); continue; }   // caught: send another
            w = r.world();
            loyalty = w.sector(THEIRS).loyalty();
            armed = w.sector(THEIRS).che() > 0;
        }
        assertThat(loyalty).as("each attempt adds " + inc.loyalty()).isGreaterThan(inc.loyalty());
        assertThat(armed).as("past the line, its people took up arms").isTrue();
        assertThat(w.sector(THEIRS).cheTarget()).as("KNOWN revolt.c: guerrillas fight whoever holds the sector").isEqualTo(1);
    }

    @Test
    void incitingNeedsASpyStandingInTheirSector() {
        assertThat(EX.execute(world("infantry", THEIRS), 0, new Command.Incite(1)).error()).contains("not a spy");
        assertThat(EX.execute(world("infiltrator", OURS), 0, new Command.Incite(1)).error()).contains("your own sector");
    }
}
