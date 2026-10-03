package org.hastingtx.empire.server.console;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.MarketLot;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.sim.Sim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #141: {@code market} as the original's mark.c lists it — the cheapest of each, one commodity, or all. */
class MarketListingTest {
    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();
    private static final Commodities COM = Commodities.of(CFG);

    @Test
    void cheapestOfEachByDefaultAndEveryLotOnRequest() {
        World w = new Sim(CFG).newWorld(List.of("P", "Q"), 7);
        Coord ours = w.country(0).capital(), theirs = w.country(1).capital();
        w = w.withLot(new MarketLot(1, 0, COM.index("iron"), 400, 2.5, MarketLot.NOBODY, ours, null, 0, 4))
             .withLot(new MarketLot(2, 1, COM.index("iron"), 100, 1.75, 0, theirs, ours, 0, 4))
             .withLot(new MarketLot(3, 1, COM.food, 900, 0.5, MarketLot.NOBODY, theirs, null, 0, 4));
        String mine = Console.market(CountryView.of(w, CFG, 0), null);
        assertThat(mine).contains("1.75").contains("0.50").doesNotContain("2.50");   // the cheaper iron lot only
        assertThat(mine).contains("your bid, to 0,0");
        String iron = Console.market(CountryView.of(w, CFG, 0), "iron");
        assertThat(iron).contains("2.50").contains("1.75").doesNotContain("0.50").contains("from 0,0");
        String theirView = Console.market(CountryView.of(w, CFG, 1), "all");
        String ourLotToThem = theirView.lines().filter(l -> l.startsWith("1 ")).findFirst().orElseThrow();
        assertThat(ourLotToThem).as("our lot's sector is ours to know").doesNotContain("from");
        assertThat(Console.market(CountryView.of(w, CFG, 0), "gold")).isEqualTo("no gold on the market");
        assertThat(Console.market(CountryView.of(new Sim(CFG).newWorld(List.of("P", "Q"), 7), CFG, 0), null)).startsWith("the market is empty");
    }
}
