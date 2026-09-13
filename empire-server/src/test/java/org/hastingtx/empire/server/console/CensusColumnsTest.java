package org.hastingtx.empire.server.console;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.sim.Sim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #156: the census carries days of food, standing deliveries, and (as `census res`) the ground. */
class CensusColumnsTest {
    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();
    private static final World W = new Sim(CFG).newWorld(List.of("P", "Q"), 7);
    private static final CountryView V = CountryView.of(W, CFG, 0);

    @Test
    void censusHasDaysAndDeliverColumns() {
        String c = Console.census(V, CFG);
        assertThat(c).startsWith("sect").contains(" days ").contains("deliver");
        assertThat(c).contains("days: updates the food lasts");
        assertThat(c.lines().count()).isGreaterThan(2);
    }

    @Test
    void censusResShowsTheGroundAndWhatItIsPoorFor() {
        String c = Console.censusResources(V, CFG);
        assertThat(c).startsWith("sect").contains("fert").contains("min").contains("poor ground for");
        assertThat(c).contains("makes that percent of what a hex at 100 would");
    }
}
