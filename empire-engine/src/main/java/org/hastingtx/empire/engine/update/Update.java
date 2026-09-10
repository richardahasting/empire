package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.steps.*;

import java.util.List;

/**
 * The update: a pure function of (snapshot, config, seed). Steps are numbered as in
 * docs/update-sequence.md. Nothing here reads a clock or does I/O.
 */
public final class Update {
    private Update() {}

    public static List<Step> steps() {
        return List.of(
                new AccrualStep(),        // 2
                new PopulationStep(),     // 3
                new BuildUpStep(),        // 4
                new ProductionStep(),     // 5
                new FlowStep(),           // 6 + 7 (plan, then resolve contention and walk)
                new ShipStep(),           // 7c ships: fit out, fish, cruise, lanes, sail (issue #56)
                new MoneyStep(),          // 8
                new LevelsStep(),         // 9
                new DetectionStep(),      // 10 (stub in M0)
                new MemoryStep(),         // 10b what each country can see goes onto its chart (issue #64)
                new NewsStep());          // 11
    }

    public static UpdateResult run(World snapshot, GameConfig cfg, long seed) {
        Commodities com = Commodities.of(cfg);
        Ctx ctx = new Ctx(snapshot, cfg, com, seed);            // 1. snapshot & normalise
        for (Step s : steps()) s.run(ctx);
        return ApplyStep.apply(ctx);                              // 12. apply + invariants + hash
    }
}
