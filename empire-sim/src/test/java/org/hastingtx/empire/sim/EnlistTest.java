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
 * Issue #276. In playtest game 82, 69 of Rick's 92 sectors had no garrison when happiness slipped; 36 revolts
 * became 295 sectors lost to partisans. Richard, 2026-10-03: in the original "you could enlist directly from each
 * sector" — Empire 1.x's enlist, straight from the civilians, no reserve. The centre is 4.x's snowball.
 */
class EnlistTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord AT = Hex.stepRaw(TestWorlds.CENTER, 1, 1);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    private static World world(String type, double civ, double mil) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        return TestWorlds.own(w, CFG, AT, type, 100, 127, Map.of("civ", civ, "mil", mil, "food", 5000.0), Map.of());
    }

    private static World world(double civ, double mil) { return world("agribusiness", civ, mil); }

    private static double mil(World w) { return w.sector(AT).stock().get(COM.mil); }

    @Test
    void anySectorOfYoursAnswersTheCallStraightFromItsCivilians() {
        CommandResult r = EX.execute(world(400, 0), 0, new Command.Enlist(AT, 20, false));
        assertThat(r.error()).as(r.error()).isNull();
        Sector s = r.world().sector(AT);
        assertThat(s.stock().get(COM.mil)).isEqualTo(20);
        assertThat(s.stock().get(COM.civ)).isEqualTo(380);
        assertThat(r.info()).contains("20.0 enlisted").contains("(20.0)");
    }

    @Test
    void theQuotaFormBringsTheGarrisonUpToTheNumber() {
        World w = EX.execute(world(400, 15), 0, new Command.Enlist(AT, 20, true)).world();
        assertThat(mil(w)).as("five more, not twenty").isEqualTo(20);
        assertThat(EX.execute(w, 0, new Command.Enlist(AT, 20, true)).error()).contains("already has 20.0 military");
    }

    @Test
    void onlyHalfTheCiviliansAnswerAndASectorHoldsAtMost999() {
        CommandResult half = EX.execute(world(100, 0), 0, new Command.Enlist(AT, 500, false));
        assertThat(mil(half.world())).isEqualTo(50);
        assertThat(half.info()).contains("500.0 sought").contains("no more than 50.0 of the 100.0 civilians");
        assertThat(mil(EX.execute(world(1000, 990), 0, new Command.Enlist(AT, 500, false)).world())).isEqualTo(999);
        assertThat(EX.execute(world(1000, 999), 0, new Command.Enlist(AT, 1, false)).error()).contains("the most a sector holds");
    }

    @Test
    void thePaperworkIsPerDrafteeAndRunsOutWithTheBtus() {
        CommandResult r = EX.execute(world(1000, 0), 0, new Command.Enlist(AT, 400, false));
        assertThat(r.btuSpent()).as("0.02 a head, no charge for the command").isEqualTo(8.0);
        assertThat(r.world().country(0).btu()).isEqualTo(640 - 8.0);

        World poor = world(1000, 0);
        poor = poor.withCountry(poor.country(0).withBtu(1.0));
        CommandResult p = EX.execute(poor, 0, new Command.Enlist(AT, 400, false));
        assertThat(mil(p.world())).as("as many as one BTU pays for").isEqualTo(50);
        assertThat(p.info()).contains("the BTUs ran out");
    }

    @Test
    void theDisloyalAndTheConqueredRefuse() {
        World w = world(400, 0);
        World restless = w.withSector(w.sector(AT).withUnrest(71, 100, Sector.NOBODY, 0, Sector.NOBODY));
        assertThat(EX.execute(restless, 0, new Command.Enlist(AT, 10, false)).error()).contains("civilians refuse to report").contains("71");
        World calm = w.withSector(w.sector(AT).withUnrest(70, 100, Sector.NOBODY, 0, Sector.NOBODY));
        assertThat(EX.execute(calm, 0, new Command.Enlist(AT, 10, false)).error()).as("70 still answers").isNull();

        assertThat(EX.execute(withThem(w).withSector(w.sector(AT).withUnrest(0, 100, 1, 0, Sector.NOBODY)), 0, new Command.Enlist(AT, 10, false)).error())
                .contains("conquered civilians will not serve");
    }

    @Test
    void theOtherRefusals() {
        assertThat(EX.execute(world(0, 0), 0, new Command.Enlist(AT, 10, false)).error()).contains("no civilians");
        assertThat(EX.execute(world(400, 0), 0, new Command.Enlist(AT, 0, false)).error()).contains("at least 1");
        assertThat(EX.execute(world(400, 0), 0, new Command.Enlist(new Coord(20, 20), 10, false)).error()).contains("do not own");
    }

    // ---- the enlistment centre (KNOWN update/sect.c enlist()) ----

    @Test
    void aCentreWithAGarrisonMakesSoldiersFasterThanAnEmptyOne() {
        double empty = mil(Update.run(world("enlistment_center", 1000, 0), CFG, 3).next());
        double garrisoned = mil(Update.run(world("enlistment_center", 1000, 100), CFG, 3).next()) - 100;
        assertThat(empty).as("an empty centre still trickles").isPositive();
        assertThat(garrisoned).as("it takes soldiers to make soldiers").isGreaterThan(empty);
    }

    @Test
    void techDoesNotMobilizePeopleAnyFaster() {
        World low = world("enlistment_center", 1000, 100);
        World high = low.withCountry(low.country(0).withLevels(new Levels(500, 0, 0, 0)));
        assertThat(mil(Update.run(high, CFG, 3).next())).as("Richard 2026-10-03: people are the same, the weapons are not")
                .isEqualTo(mil(Update.run(low, CFG, 3).next()));
    }

    @Test
    void theCentreMakesTheOriginalsNumber() {
        // etu × (10 + mil) × 0.05 = 60 × 110 × 0.05 = 330, well under civ / 2 − mil = 400
        assertThat(mil(Update.run(world("enlistment_center", 1000, 100), CFG, 3).next())).isEqualTo(100 + 330);
    }

    @Test
    void aCentreStopsAtHalfItsCivilians() {
        World after = Update.run(world("enlistment_center", 1000, 480), CFG, 3).next();
        assertThat(mil(after)).isLessThanOrEqualTo(500);
    }

    @Test
    void aConqueredCentreMakesNobody() {
        World w = withThem(world("enlistment_center", 1000, 100));
        w = w.withSector(w.sector(AT).withUnrest(0, 100, 1, 0, Sector.NOBODY));
        assertThat(mil(Update.run(w, CFG, 3).next())).isLessThanOrEqualTo(100);
    }

    @Test
    void theBooksBalanceAndTheCentrePaysPerSoldier() {
        World before = world("enlistment_center", 1000, 100);
        var r = Update.run(before, CFG, 3);   // conservation is checked inside the update; a leak throws
        double made = mil(r.next()) - 100;
        assertThat(made).isPositive();
        assertThat(r.next().sector(AT).stock().get(COM.civ) + mil(r.next())).as("people are converted, not created").isLessThanOrEqualTo(1100 + 1000 * 0.1);
    }

    private static World withThem(World w) {
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", new Coord(20, 20), 100000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        return w.withCountries(cs);
    }
}
