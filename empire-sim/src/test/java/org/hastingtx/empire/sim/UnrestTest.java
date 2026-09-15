package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.steps.MoneyStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Issue #72, the original's unrest (update/populace.c, human.c, revolt.c, subs/takeover.c, commands/anti.c; Richard
 * 2026-09-15: the original is the default). Loyalty 0 is loyal; hunger and occupation make people disloyal; disloyal
 * civilians stop working and revolt; guerrillas fight, sabotage, recruit, spread, and hand a sector back.
 */
class UnrestTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord AT = Hex.stepRaw(CAP, 1, 1);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    /** Us at the centre and a second country, Them, who once held AT; AT given the stock and the unrest named. */
    private static World world(Map<String, Double> stock, int loyalty, int work, int oldOwner, int che, int cheTarget) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", new Coord(2, 2), 1000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = TestWorlds.own(w, CFG, AT, "agribusiness", 100, 100, stock, Map.of());
        return w.withSector(w.sector(AT).withUnrest(loyalty, work, oldOwner, che, cheTarget));
    }

    @Test
    void hungerBreedsDisloyaltyAndStopsWorkWhichComesBackOnceFed() {
        World w = world(Map.of("civ", 800.0), 0, 100, Sector.NOBODY, 0, Sector.NOBODY);
        w = w.withSector(w.sector(AT).withTerrain(Terrain.PLAINS, 100, Resources.NONE));      // nothing to forage: they starve
        World hungry = Update.run(w, CFG, 1).next();
        assertThat(hungry.sector(AT).loyalty()).as("roll(8) + 1").isBetween(2, 9);
        assertThat(hungry.sector(AT).work()).as("starving people do not work next update").isZero();

        World fed = hungry.withSector(hungry.sector(AT).withStock(hungry.sector(AT).stock().plus(COM.food, 5000)));
        World after = Update.run(fed, CFG, 2).next();
        assertThat(after.sector(AT).work()).as("fed, work comes back 7 + roll(15) a update").isBetween(8, 22);
    }

    @Test
    void workCountsOnlyTheCiviliansAtWork() {
        World full = world(Map.of("civ", 1000.0, "food", 5000.0), 0, 100, Sector.NOBODY, 0, Sector.NOBODY);
        World half = world(Map.of("civ", 1000.0, "food", 5000.0), 0, 50, Sector.NOBODY, 0, Sector.NOBODY);
        int i = full.index(AT);
        double a = new Ctx(full, CFG, COM, 1).workAvailablePost(i), b = new Ctx(half, CFG, COM, 1).workAvailablePost(i);
        assertThat(b).as("KNOWN total_work: civ × sct_work / 100").isCloseTo(a / 2, within(1e-6));
    }

    @Test
    void capturedCiviliansPayAQuarterTax() {
        double loyal = tax(world(Map.of("civ", 400.0, "food", 5000.0), 0, 100, Sector.NOBODY, 0, Sector.NOBODY));
        double occupied = tax(world(Map.of("civ", 400.0, "food", 5000.0), 50, 100, 1, 0, Sector.NOBODY));
        assertThat(occupied).isCloseTo(loyal / CFG.economy().unrest().capture().occupiedTaxDivisor(), within(1e-6));
    }

    private static double tax(World w) {
        World only = w.withSector(w.sector(CAP).withStock(Stocks.zero(COM.size())));
        Ctx ctx = new Ctx(only, CFG, COM, 1);
        new MoneyStep().run(ctx);
        return ctx.led().cash[0];
    }

    @Test
    void guerrillasWithNoGarrisonHandTheSectorBackToWhoseItWas() {
        // occupied, disloyal, no military, guerrillas fighting us: revolutionary subversion, and the sector converts
        World w = world(Map.of("civ", 600.0, "food", 5000.0), 80, 100, 1, 40, 0);
        World after = Update.run(w, CFG, 3).next();
        Sector s = after.sector(AT);
        assertThat(s.owner()).as("partisans took it back for Them").isEqualTo(1);
        assertThat(s.distCenter()).as("its wiring to us is gone").isNull();
        assertThat(s.stock().get(COM.mil)).as("a twentieth of the people became its military").isGreaterThan(0);
        assertThat(s.occupied()).as("its people are Them's own again").isFalse();
    }

    @Test
    void aGarrisonOutnumberedFightsAndOneFarStrongerDrivesThemNextDoor() {
        World fight = Update.run(world(Map.of("civ", 600.0, "mil", 30.0, "food", 5000.0), 20, 100, Sector.NOBODY, 90, 0), CFG, 4).next();
        Sector f = fight.sector(AT);
        assertThat(f.stock().get(COM.mil) < 30 || f.che() < 90).as("they shot it out").isTrue();

        // 500 soldiers against 20 che: ratio 25, so the che move to a neighbour of ours with a thinner garrison
        World strong = world(Map.of("civ", 600.0, "mil", 500.0, "food", 5000.0), 20, 100, Sector.NOBODY, 20, 0);
        Coord next = Hex.stepRaw(AT, 3, 1);
        strong = TestWorlds.own(strong, CFG, next, "agribusiness", 100, 100, Map.of("civ", 300.0, "food", 5000.0), Map.of());
        World moved = Update.run(strong, CFG, 5).next();
        int cheNearby = 0;
        for (Coord n : Hex.neighbours(moved, AT)) cheNearby += moved.sector(n).che();
        assertThat(moved.sector(AT).che() < 20 || cheNearby > 0).as("caught, or gone next door").isTrue();
    }

    @Test
    void antiClearsThemOutOrLosesTheSector() {
        World w = world(Map.of("civ", 600.0, "mil", 300.0, "food", 5000.0), 30, 100, Sector.NOBODY, 20, 0);
        w = w.withSector(w.sector(AT).withMobility(127));
        CommandResult won = EX.execute(w, 0, new Command.Anti(AT));
        assertThat(won.error()).as(won.error()).isNull();
        assertThat(won.world().sector(AT).che()).as("300 soldiers against 20").isLessThan(20);
        assertThat(won.world().sector(AT).owner()).isZero();

        World weak = world(Map.of("civ", 600.0, "mil", 3.0, "food", 5000.0), 60, 100, 1, 200, 0);
        weak = weak.withSector(weak.sector(AT).withMobility(127));
        CommandResult lost = EX.execute(weak, 0, new Command.Anti(AT));
        assertThat(lost.error()).as(lost.error()).isNull();
        assertThat(lost.world().sector(AT).owner()).as("three soldiers against two hundred: the partisans take it for Them").isEqualTo(1);
        assertThat(lost.info()).contains("You blew it");

        assertThat(EX.execute(world(Map.of("civ", 600.0, "mil", 30.0), 0, 100, Sector.NOBODY, 0, Sector.NOBODY), 0, new Command.Anti(AT)).error())
                .contains("no guerrillas");
    }

    @Test
    void anOccupiedSectorComesRoundInTime() {
        World w = world(Map.of("civ", 600.0, "mil", 100.0, "food", 9000.0), 50, 100, 1, 0, Sector.NOBODY);
        boolean yours = false;
        for (int u = 0; u < 40 && !yours; u++) {
            w = Update.run(w, CFG, 100 + u).next();
            w = w.withSector(w.sector(AT).withStock(w.sector(AT).stock().with(COM.food, 9000)));
            yours = !w.sector(AT).occupied();
        }
        assertThat(yours).as("loyalty decays to 0 and the people are ours").isTrue();
        assertThat(w.sector(AT).loyalty()).isZero();
    }
}
