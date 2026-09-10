package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.view.CountryView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standing rail runs (issue #70). A one-off {@code railship} is consumed at the update; a lane stands,
 * and the point of it is that nobody has to type anything again.
 */
class RailLaneTest {
    private static final int IRON = 4;
    private static final GameConfig CFG = TestWorlds.teaching();

    /** Two depots five hexes apart on good track, the near one full of iron and the far one empty. */
    private static World line() {
        World w = TestWorlds.disc(CFG, 7, Map.of("civ", 500.0, "food", 800.0, "iron", 5000.0, "lcm", 500.0));
        w = w.withCountry(w.country(0).withLevels(new Levels(70, 0, 0, 0)).withCash(500000));
        Coord a = TestWorlds.CENTER;
        w = w.withSector(w.sector(a).withDesignation("depot", 100).withRailLevel(100));
        for (int k = 1; k <= 5; k++) {
            Coord c = Hex.stepRaw(a, 0, k);
            w = TestWorlds.own(w, CFG, c, k == 5 ? "depot" : "agribusiness", 100, 127, Map.of("civ", 100.0, "food", 300.0), Map.of());
            w = w.withSector(w.sector(c).withRailLevel(100));
        }
        return w;
    }

    private static final Coord A = TestWorlds.CENTER, B = Hex.stepRaw(TestWorlds.CENTER, 0, 5);

    @Test
    void aLaneRunsEveryUpdateWithNobodyOrderingIt() {
        CommandExecutor exec = new CommandExecutor(CFG);
        CommandResult r = exec.execute(line(), 0, new Command.RailLane(A, B, List.of("iron"), false));
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.info()).contains("every update");

        World w = r.world();
        assertThat(w.sector(B).stock().get(IRON)).isZero();
        w = Update.run(w, CFG, 5).next();
        double afterOne = w.sector(B).stock().get(IRON);
        assertThat(afterOne).as("the lane ran without an order").isGreaterThan(0.0);

        // the first run emptied the near depot, so restock it and let the lane run again with
        // nothing typed in between — that is what makes it a lane rather than an order
        Sector a = w.sector(A);
        w = w.withSector(a.withStock(a.stock().with(IRON, 900)));
        w = Update.run(w, CFG, 6).next();
        assertThat(w.sector(B).stock().get(IRON)).as("and again, unprompted").isGreaterThan(afterOne);
        assertThat(w.railLanes()).as("a lane stands until it is cancelled").hasSize(1);
    }

    @Test
    void aLaneWithNoCargoKeepsTheFarEndsThresholdsToppedUp() {
        // B wants 400 iron and has none; A has thousands. Nobody names a commodity anywhere.
        World w = line();
        w = w.withSector(w.sector(B).withThreshold(IRON, 400));
        CommandExecutor exec = new CommandExecutor(CFG);
        CommandResult r = exec.execute(w, 0, new Command.RailLane(A, B, List.of(), false));
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.info()).contains("topped up");

        World after = Update.run(r.world(), CFG, 5).next();
        assertThat(after.sector(B).stock().get(IRON))
                .as("the depot filled its own threshold over the network")
                .isGreaterThan(0.0);
        assertThat(after.sector(B).stock().get(IRON)).isLessThanOrEqualTo(400.0);
    }

    @Test
    void aLaneNeverDrainsTheSourceBelowItsOwnThreshold() {
        World w = line();
        w = w.withSector(w.sector(A).withThreshold(IRON, 4800));   // A keeps 4800 of its 5000
        w = w.withSector(w.sector(B).withThreshold(IRON, 1000));   // B would happily take 1000
        CommandExecutor exec = new CommandExecutor(CFG);
        World after = Update.run(exec.execute(w, 0, new Command.RailLane(A, B, List.of(), false)).world(), CFG, 5).next();

        assertThat(after.sector(A).stock().get(IRON))
                .as("a threshold is what a sector keeps, and a lane respects it")
                .isGreaterThanOrEqualTo(4800.0);
        assertThat(after.sector(B).stock().get(IRON)).isLessThanOrEqualTo(200.0);
    }

    @Test
    void aLaneCanBeCancelled() {
        CommandExecutor exec = new CommandExecutor(CFG);
        World w = exec.execute(line(), 0, new Command.RailLane(A, B, List.of("iron"), false)).world();
        assertThat(w.railLanes()).hasSize(1);
        CommandResult off = exec.execute(w, 0, new Command.RailLane(A, B, List.of(), true));
        assertThat(off.ok()).isTrue();
        assertThat(off.info()).contains("cancelled");
        assertThat(off.world().railLanes()).isEmpty();
    }

    @Test
    void aLaneNeedsTwoWorkingDepotsAndALineBetweenThem() {
        CommandExecutor exec = new CommandExecutor(CFG);
        Coord notADepot = Hex.stepRaw(TestWorlds.CENTER, 2, 3);
        CommandResult r = exec.execute(line(), 0, new Command.RailLane(A, notADepot, List.of(), false));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).containsAnyOf("depot", "own");
    }

    @Test
    void theViewShowsTheLaneAndAnyTrainOnIt() {
        CommandExecutor exec = new CommandExecutor(CFG);
        World w = exec.execute(line(), 0, new Command.RailLane(A, B, List.of("iron"), false)).world();
        CountryView v = CountryView.of(w, CFG, 0);
        assertThat(v.railLanes()).hasSize(1);
        assertThat(v.railLanes().get(0).from()).isEqualTo(A);
        assertThat(v.railLanes().get(0).to()).isEqualTo(B);
        assertThat(v.railLanes().get(0).cargo()).containsExactly("iron");
    }
}
