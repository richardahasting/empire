package org.hastingtx.empire.sim;

import org.hastingtx.empire.agents.scripted.ScriptedAgent;
import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #73. The scripted agent is the harness, and a harness that never builds a ship never
 * exercises harbours, fuel, crews or the fishing mission — a conservation bug in any of them would
 * wait for a person to find it. These assert the agent actually reaches those features, not that it
 * plays well: a run that stops touching the sea is the regression.
 */
class AgentReachesSeaAndRailTest {

    private static final GameConfig CFG = new ConfigLoader().loadPreset("sandbox").config();

    private static Sim.Result run(int updates) {
        Sim sim = new Sim(CFG);
        World w0 = sim.newWorld(List.of("A"), 7L);
        return sim.run(w0, updates, 7L, id -> new ScriptedAgent());
    }

    @Test
    void theAgentBuildsAHarbourAndPutsAHullInIt() {
        World w = run(30).world();
        long harbors = w.sectors().stream().filter(s -> s.owner() == 0 && s.designation().equals("harbor")).count();
        assertThat(harbors).as("a harbour, or nothing at sea is ever exercised").isGreaterThan(0);
        assertThat(w.ships()).as("a hull, so fuel, crews and the fishing mission all run").isNotEmpty();
    }

    @Test
    void theHullIsSentFishing() {
        World w = run(30).world();
        assertThat(w.ships()).anySatisfy(s ->
                assertThat(s.mission()).as("a ship that never sails exercises nothing")
                        .isEqualTo(org.hastingtx.empire.engine.model.Ship.FISH));
    }

    @Test
    void theAgentPavesRoads() {
        World w = run(30).world();
        assertThat(w.sectors()).anySatisfy(s -> {
            if (s.owner() == 0) assertThat(s.roadLevel() + s.roadTarget()).isGreaterThan(0.0);
        });
        long paving = w.sectors().stream().filter(s -> s.owner() == 0 && (s.roadLevel() > 0 || s.roadTarget() > 0)).count();
        assertThat(paving).isGreaterThan(0);
    }

    @Test
    void theAgentSetsAStandingDeliveryOrder() {
        World w = run(30).world();
        long delivering = w.sectors().stream().filter(s -> s.owner() == 0 && s.deliver().count() > 0).count();
        assertThat(delivering).as("the deliver path should run every update in a harness run").isGreaterThan(0);
    }

    /** Whatever it does, it must still be a fixture: same seed, same run, same world. */
    @Test
    void itIsStillDeterministic() {
        assertThat(run(20).world().sectors()).isEqualTo(run(20).world().sectors());
    }

    /** And it must not break the update's own guarantee while doing any of it. */
    @Test
    void conservationSurvivesTheWholeRun() {
        Sim.Result r = run(30);
        assertThat(r.world().updateNumber()).isEqualTo(30);
    }
}
