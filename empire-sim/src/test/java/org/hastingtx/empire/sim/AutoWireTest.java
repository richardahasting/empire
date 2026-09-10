package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Designating a sector puts it to work (issue #99).
 *
 * <p>The acceptance test is the downstream one: not that a centre and some thresholds were written,
 * but that iron actually reaches the warehouse at the next update with nothing else typed. A
 * distribution centre without thresholds moves nothing, which is the whole reason this does both.
 */
class AutoWireTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int IRON = COM.index("iron");
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord SHED = Hex.stepRaw(CAP, 0, 1);      // the warehouse
    private static final Coord PIT = Hex.stepRaw(CAP, 0, 2);       // becomes a mine, two hexes out

    /** A capital, a warehouse next door, and a plain owned sector beyond it. */
    private static World world() {
        World w = TestWorlds.disc(CFG, 4, Map.of("civ", 900.0, "food", 900.0));
        w = w.withCountry(w.country(0).withCash(200000));
        w = TestWorlds.own(w, CFG, SHED, "warehouse", 100, 127, Map.of("civ", 500.0, "food", 500.0), Map.of());
        w = TestWorlds.own(w, CFG, PIT, "wilderness", 100, 127, Map.of("civ", 700.0, "food", 700.0, "iron", 900.0), Map.of());
        // TestWorlds.own points every sector at the capital; an unwired sector is the case under test
        return w.withSector(w.sector(PIT).withDistCenter(null));
    }

    @Test
    void designatingPointsTheSectorAtTheNearestHubAndSetsItsThresholds() {
        CommandResult r = new CommandExecutor(CFG).execute(world(), 0, new Command.Designate(PIT, "mine"));
        assertThat(r.ok()).as(r.error()).isTrue();
        Sector pit = r.world().sector(PIT);

        assertThat(pit.distCenter()).describedAs("pointed at the warehouse next door").isEqualTo(SHED);
        assertThat(pit.threshold(IRON)).describedAs("keeps none of what it digs — the surplus is the point").isZero();
        assertThat(pit.hasThreshold(COM.civ)).isTrue();
        assertThat(pit.hasThreshold(COM.food)).isTrue();
        assertThat(r.info()).contains("surplus goes to").contains("thresholds");
    }

    @Test
    void theIronActuallyArrivesWithNothingElseTyped() {
        World w = new CommandExecutor(CFG).execute(world(), 0, new Command.Designate(PIT, "mine")).world();
        double shedBefore = w.sector(SHED).stock().get(IRON);

        World after = Update.run(w, CFG, 7).next();

        assertThat(after.sector(SHED).stock().get(IRON))
                .describedAs("the mine's surplus reached the warehouse without another command")
                .isGreaterThan(shedBefore);
        assertThat(after.sector(PIT).stock().get(IRON)).isLessThan(900.0);
    }

    @Test
    void aCentreThePlayerChoseIsNeverStomped() {
        CommandExecutor exec = new CommandExecutor(CFG);
        World w = exec.execute(world(), 0, new Command.Distribute(PIT, CAP)).world();   // deliberately the capital
        World after = exec.execute(w, 0, new Command.Designate(PIT, "mine")).world();
        assertThat(after.sector(PIT).distCenter())
                .describedAs("re-designating must not undo a deliberate distribute")
                .isEqualTo(CAP);
    }

    @Test
    void aThresholdThePlayerSetIsNeverStomped() {
        CommandExecutor exec = new CommandExecutor(CFG);
        World w = exec.execute(world(), 0, new Command.Threshold(PIT, "iron", 750)).world();
        World after = exec.execute(w, 0, new Command.Designate(PIT, "mine")).world();
        assertThat(after.sector(PIT).threshold(IRON))
                .describedAs("the player said 750 and meant it")
                .isEqualTo(750.0);
    }

    @Test
    void withNoHubToPointAtItStillSetsThresholds() {
        // no warehouse anywhere: the sector is designated and given thresholds, just no centre
        World bare = TestWorlds.disc(CFG, 4, Map.of("civ", 900.0, "food", 900.0));
        bare = TestWorlds.own(bare, CFG, PIT, "wilderness", 100, 127, Map.of("civ", 700.0, "food", 700.0), Map.of());
        bare = bare.withSector(bare.sector(PIT).withDistCenter(null));
        CommandResult r = new CommandExecutor(CFG).execute(bare, 0, new Command.Designate(PIT, "mine"));
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.world().sector(PIT).distCenter()).isNull();
        assertThat(r.world().sector(PIT).hasThreshold(IRON)).isTrue();
    }

    @Test
    void aConsumerKeepsAWorkingStockOfWhatItEats() {
        // light manufacturing consumes iron; it should keep some rather than shipping it all out
        World w = new CommandExecutor(CFG).execute(world(), 0, new Command.Designate(PIT, "light_manufacturing")).world();
        Sector pit = w.sector(PIT);
        assertThat(pit.threshold(IRON)).describedAs("an input is kept topped up, not sent away").isGreaterThan(0.0);
        assertThat(pit.threshold(COM.index("lcm"))).describedAs("but what it makes still leaves").isZero();
    }
}
