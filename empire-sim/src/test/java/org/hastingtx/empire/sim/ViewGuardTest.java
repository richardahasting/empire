package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.view.CountryView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The one careless field. No projection handed to a player may say who is a bot. */
class ViewGuardTest {
    @Test
    void countryViewHasNoControllerInformation() {
        for (Class<?> k : List.of(CountryView.class, CountryView.SectorView.class))
            for (RecordComponent rc : k.getRecordComponents())
                assertThat(rc.getName().toLowerCase()).doesNotContain("controller").doesNotContain("agent").doesNotContain("bot").doesNotContain("human");
    }

    @Test
    void viewShowsOwnSectorsFullyAndNeighboursOnlyByTerrain() {
        GameConfig cfg = TestWorlds.teaching();
        Sim sim = new Sim(cfg);
        World w = sim.newWorld(List.of("A", "B"), 3);
        CountryView v = CountryView.of(w, cfg, 0);
        assertThat(v.sectors()).anyMatch(CountryView.SectorView::full);
        assertThat(v.sectors()).anyMatch(s -> !s.full());
        for (var s : v.sectors()) if (!s.full()) { assertThat(s.stock()).isEmpty(); assertThat(s.designation()).isNull(); }
        assertThat(v.otherCountryNames()).containsExactly("B");
        assertThat(v.toString()).doesNotContain("controller");
    }
}
