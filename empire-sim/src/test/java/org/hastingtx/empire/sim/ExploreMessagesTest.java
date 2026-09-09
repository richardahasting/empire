package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Routes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #54: a sanctuary is named as such, not as "already owned". */
class ExploreMessagesTest {
    @Test
    void exploringIntoASanctuarySaysSo() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 2, Map.of("civ", 500.0, "food", 1000.0));
        Coord next = Hex.stepRaw(TestWorlds.CENTER, 0, 1);
        w = w.withSector(w.sector(next).withOwner(1).withSanctuary(true));   // somebody else's sanctuary next door
        assertThat(new CommandExecutor(cfg).execute(w, 0, new Command.Explore(TestWorlds.CENTER, next, 20)).error()).contains("sanctuary");
        Routes.Estimate e = Routes.explore(w, cfg, 0, TestWorlds.CENTER, next, 20);
        assertThat(e.ok()).isFalse();
        assertThat(e.error()).contains("sanctuary");
    }
}
