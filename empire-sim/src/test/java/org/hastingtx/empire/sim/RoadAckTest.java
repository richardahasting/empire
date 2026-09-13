package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #150: a road order is accepted at once but paves nothing until a whole point is affordable — the ack says so. */
class RoadAckTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Coord CAP = TestWorlds.CENTER;

    private static World with(Map<String, Double> stock) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 100.0, "food", 500.0));
        Coord a = Hex.stepRaw(CAP, 0, 1);
        return TestWorlds.own(w, CFG, a, "agribusiness", 100, 127, stock, Map.of());
    }

    @Test
    void anOrderWithNoMaterialsSaysWhatIsNeededBeforeAnythingIsLaid() {
        Coord a = Hex.stepRaw(CAP, 0, 1);
        CommandResult r = new CommandExecutor(CFG).execute(with(Map.of("civ", 100.0, "food", 500.0)), 0, new Command.BuildRoad(a, 100));
        assertThat(r.ok()).as("the standing order is still accepted").isTrue();
        assertThat(r.world().sector(a).roadTarget()).isGreaterThan(0);
        assertThat(r.info()).contains("ordered to").contains("a point here").contains("NEEDS").contains("more lcm").contains("more hcm").contains("nothing happens until then");
    }

    @Test
    void anOrderWithMaterialsSaysHowManyPointsComeNextUpdate() {
        Coord a = Hex.stepRaw(CAP, 0, 1);
        CommandResult r = new CommandExecutor(CFG).execute(with(Map.of("civ", 100.0, "food", 500.0, "lcm", 100.0, "hcm", 100.0)), 0, new Command.BuildRoad(a, 100));
        assertThat(r.ok()).isTrue();
        assertThat(r.info()).contains("enough for").contains("next update").doesNotContain("NEEDS");
    }

    @Test
    void cancellingSaysSo() {
        Coord a = Hex.stepRaw(CAP, 0, 1);
        CommandExecutor ex = new CommandExecutor(CFG);
        World w = ex.execute(with(Map.of("civ", 100.0, "food", 500.0)), 0, new Command.BuildRoad(a, 100)).world();
        CommandResult r = ex.execute(w, 0, new Command.BuildRoad(a, 0));
        assertThat(r.ok()).isTrue();
        assertThat(r.info()).contains("cancelled");
        assertThat(r.world().sector(a).roadTarget()).isZero();
    }
}
