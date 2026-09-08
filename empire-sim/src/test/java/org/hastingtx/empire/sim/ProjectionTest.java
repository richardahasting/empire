package org.hastingtx.empire.sim;

import org.hastingtx.empire.agents.scripted.ScriptedAgent;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Projection;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The budget projection is the update itself, so it must match the update to the cent. */
class ProjectionTest {
    @Test
    void projectionEqualsTheRealUpdate() {
        GameConfig cfg = TestWorlds.teaching();
        Sim sim = new Sim(cfg);
        World w = sim.run(sim.newWorld(List.of("A", "B"), 21), 6, 21, id -> new ScriptedAgent()).world();
        Projection.Result p = Projection.of(w, cfg, 0, 777);
        World next = Update.run(w, cfg, 777).next();
        assertThat(p.cashAfter()).isCloseTo(next.country(0).cash(), within(1e-9));
        assertThat(p.btuAfter()).isCloseTo(next.country(0).btu(), within(1e-9));
        assertThat(p.forUpdate()).isEqualTo(w.updateNumber() + 1);
        assertThat(p.cashNow()).isEqualTo(w.country(0).cash());
    }
}
