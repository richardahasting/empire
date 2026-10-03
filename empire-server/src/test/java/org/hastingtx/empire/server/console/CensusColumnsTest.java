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

    /**
     * Issue #275: a city at 0% with 5,500 civilians and 100,000 iron had nothing on the census page to say why. The
     * last update knew — short of lcm — and so does the unrest view; the census says both.
     */
    @Test
    void censusSaysWhatHoldsASectorBack() {
        var cap = W.country(0).capital();
        org.hastingtx.empire.engine.model.Coord at = null, calm = null;
        for (var n : org.hastingtx.empire.engine.geo.Hex.neighbours(W, cap)) {
            if (n.equals(cap) || !W.sector(n).isLand()) continue;
            if (at == null) at = n; else if (calm == null) calm = n;
        }
        World w = W.withSector(W.sector(at).withOwner(0).withDesignation("city", 0).withUnrest(70, 40, org.hastingtx.empire.engine.model.Sector.NOBODY, 5, 0))
                   .withSector(W.sector(calm).withOwner(0).withDesignation("city", 0));
        CountryView v = CountryView.of(w, CFG, 0);
        var r = CountryView.relative(w, cap, at);
        String key = r.x() + "," + r.y();
        String c = Console.census(v, CFG, java.util.Map.of(key, List.of("people ate 164 food", "shortage: 2500 civ, 4100 lcm")), List.of());
        assertThat(c).contains("held back: " + key + " cit 0%: short of 2500 civ, 4100 lcm")
                .contains("disloyal (loyalty 70, above ").contains("40% at work").contains("5 guerrillas");
        var q = CountryView.relative(w, cap, calm);
        assertThat(c).as("0% with no reason known is not listed").doesNotContain(q.x() + "," + q.y() + " cit 0%");
        assertThat(Console.census(V, CFG)).as("nothing held back, no line").doesNotContain("held back:");
    }

    /** Issue #268: sectors vanished between updates "without combat" — partisans took them, and nothing on the page said so. */
    @Test
    void censusSaysWhatTheLastUpdateTookAway() {
        String c = Console.census(V, CFG, java.util.Map.of(), List.of("u3602 partisans take over 4,4", "u3643 revolt in 5,1"));
        assertThat(c).contains("in the last " + Console.LOSS_WINDOW + " updates: u3602 partisans take over 4,4; u3643 revolt in 5,1 — guerrillas grow where there are no soldiers; enlist SECTOR -N puts a garrison in");
        assertThat(Console.census(V, CFG)).doesNotContain("updates: u");
    }

    @Test
    void censusResShowsTheGroundAndWhatItIsPoorFor() {
        String c = Console.censusResources(V, CFG);
        assertThat(c).startsWith("sect").contains("fert").contains("min").contains("poor ground for");
        assertThat(c).contains("makes that percent of what a hex at 100 would");
    }
}
