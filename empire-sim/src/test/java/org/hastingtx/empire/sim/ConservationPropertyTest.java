package org.hastingtx.empire.sim;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.hastingtx.empire.agents.scripted.ScriptedAgent;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.HeldParcel;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property: for any seed and any number of prior updates, one more update never creates a
 * negative stock, never exceeds capacity, and (the engine's own apply-time check) conserves
 * every commodity up to its documented sources and sinks. The apply step throws on violation,
 * so the property is "the update completes"; the explicit assertions repeat the invariants.
 */
class ConservationPropertyTest {
    private static final GameConfig CFG = TestWorlds.teaching();

    @Property(tries = 25)
    void updateNeverBreaksInvariants(@ForAll("seeds") long seed, @ForAll @IntRange(min = 0, max = 12) int warmup) {
        Sim sim = new Sim(CFG);
        World w = sim.newWorld(List.of("P", "Q"), seed);
        if (warmup > 0) w = sim.run(w, warmup, seed, id -> new ScriptedAgent()).world();
        UpdateResult r = Update.run(w, CFG, seed ^ 0xABCDEFL);
        double mobMax = CFG.economy().mobility().sectorMax();
        for (Sector s : r.next().sectors()) {
            for (int c = 0; c < s.stock().size(); c++) assertThat(s.stock().get(c)).isGreaterThanOrEqualTo(0.0);
            for (HeldParcel p : s.held()) assertThat(p.qty()).isGreaterThan(0.0);
            assertThat(s.efficiency()).isBetween(0.0, 100.0);
            assertThat(s.mobility()).isBetween(0.0, mobMax);
        }
        assertThat(r.next().updateNumber()).isEqualTo(w.updateNumber() + 1);
    }

    @Provide
    Arbitrary<Long> seeds() { return Arbitraries.longs().between(1, 1_000_000); }
}
