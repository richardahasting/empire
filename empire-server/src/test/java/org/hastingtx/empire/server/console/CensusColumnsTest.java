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
        assertThat(c).as("a fleet's stocks (issue #196)").contains(" pet ").contains(" gun ").contains("shell").contains("country: pet ");
        assertThat(c).startsWith("sect").contains(" days ").contains("deliver");
        assertThat(c).contains("days: updates the food lasts");
        assertThat(c.lines().count()).isGreaterThan(2);
    }

    /** Richard 2026-09-15: "A radar should note the radius of its vision." The census says how far each station sees. */
    @Test
    void censusSaysHowFarEachRadarSees() {
        assertThat(Console.census(V, CFG)).as("no radar, no line").doesNotContain("radar:");
        var cap = W.country(0).capital();
        org.hastingtx.empire.engine.model.Coord at = null;
        for (var n : org.hastingtx.empire.engine.geo.Hex.neighbours(W, cap)) if (W.sector(n).owner() == 0 && !n.equals(cap)) { at = n; break; }
        World w = W.withSector(W.sector(at).withDesignation("radar", 100));
        CountryView v = CountryView.of(w, CFG, 0);
        int reach = (int) Math.floor(org.hastingtx.empire.engine.view.Radar.range(CFG, w.sector(at), w.country(0).levels().tech()));
        assertThat(reach).isGreaterThanOrEqualTo(1);
        assertThat(Console.census(v, CFG)).contains("radar: ").contains("sees " + reach + " hexes");
    }

    @Test
    void censusResShowsTheGroundAndWhatItIsPoorFor() {
        String c = Console.censusResources(V, CFG);
        assertThat(c).startsWith("sect").contains("fert").contains("min").contains("poor ground for");
        assertThat(c).contains("makes that percent of what a hex at 100 would");
    }
}
