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

/**
 * Issue #268: land lost and settled again keeps what it was — a harbour, a bank — and the explore ack did not say so,
 * so the next order was often a blind {@code des … agribusiness} that flattened it. Now the ack says it first.
 */
class ExploreRemembersTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Coord CAP = TestWorlds.CENTER;

    @Test
    void settlingLandThatWasBuiltUpSaysWhatItStillIs() {
        World w = TestWorlds.disc(CFG, 4, Map.of("civ", 500.0, "food", 500.0));
        Coord lost = Hex.stepRaw(CAP, 3, 1), wild = Hex.stepRaw(CAP, 4, 1);
        w = w.withSector(w.sector(lost).withDesignation("harbor", 82));
        assertThat(w.sector(lost).owned()).isFalse();
        CommandExecutor ex = new CommandExecutor(CFG);

        CommandResult r = ex.execute(w, 0, new Command.Explore(CAP, lost, 10));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).contains("it is still a harbor at 82% from before it was lost");
        assertThat(r.world().sector(lost).designation()).isEqualTo("harbor");

        CommandResult plain = ex.execute(w, 0, new Command.Explore(CAP, wild, 10));
        assertThat(plain.error()).as(plain.error()).isNull();
        assertThat(plain.info()).doesNotContain("still a");
    }
}
