package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Resources;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Issues #157 and #158: the things a player is allowed to do that they will regret are said out loud. */
class WarningsTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord A = Hex.stepRaw(CAP, 0, 1), B = Hex.stepRaw(CAP, 0, 2);

    private static World base() {
        World w = TestWorlds.disc(CFG, 3, Map.of("civ", 100.0, "food", 1000.0));
        w = TestWorlds.own(w, CFG, A, "wilderness", 100, 127, Map.of("civ", 800.0, "food", 50.0), Map.of());
        return TestWorlds.own(w, CFG, B, "wilderness", 100, 127, Map.of("civ", 10.0, "food", 0.0), Map.of());
    }

    @Test
    void anAliasDesignatesAndTheAckSaysWhatItMeant() {
        CommandResult r = new CommandExecutor(CFG).execute(base(), 0, new Command.Designate(A, "manufacturer"));
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.world().sector(A).designation()).isEqualTo("light_manufacturing");
        assertThat(r.info()).startsWith("now light_manufacturing (j) — 'manufacturer' means light_manufacturing");
        assertThat(CFG.resolveSectorType("j").id()).isEqualTo("light_manufacturing");
        assertThat(CFG.resolveSectorType("nope")).isNull();
    }

    @Test
    void aMineOnPoorGroundIsAllowedButWarns() {
        World w = base();
        Sector s = w.sector(A);
        w = w.withSector(s.withTerrain(s.terrain(), s.elevation(), new Resources(80, 8, 10, 10, 5)));
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Designate(A, "mine"));
        assertThat(r.ok()).isTrue();
        assertThat(r.info()).contains("WARNING").contains("minerals 8 is poor ground for mine").contains("about 8%");
        // the disc's default 40 minerals is not poor: no warning
        CommandResult fine = new CommandExecutor(CFG).execute(base(), 0, new Command.Designate(A, "mine"));
        assertThat(fine.info()).doesNotContain("WARNING");
        assertThat(CFG.economy().poorGroundBelowOrDefault()).isEqualTo(30.0);
    }

    @Test
    void foodOutOfASectorThatKeepsLessThanItEatsWarns() {
        CommandExecutor ex = new CommandExecutor(CFG);
        // 800 people on A, keep nothing: a pipe that starves its source
        CommandResult r = ex.execute(base(), 0, new Command.Deliver(A, "food", 0, 0));
        assertThat(r.ok()).as("allowed — the player may mean it").isTrue();
        assertThat(r.info()).contains("WARNING").contains("eat about").contains("keeps only 0.0");
        // keep plenty: no warning
        CommandResult ok = ex.execute(base(), 0, new Command.Deliver(A, "food", 0, 5000));
        assertThat(ok.info()).doesNotContain("WARNING");
        // iron is not food: never a warning
        CommandResult iron = ex.execute(base(), 0, new Command.Deliver(A, "iron", 0, 0));
        assertThat(iron.info()).doesNotContain("WARNING");
    }
}
