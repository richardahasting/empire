package org.hastingtx.empire.server.console;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.sim.Sim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playtest game 82 (issues #154, #155). The only way to learn what lay in a direction used to be to
 * explore into it with one civilian — claiming a starve shell — or to write a delivery order and read
 * the refusal. These two verbs answer from the player's own view and write nothing.
 */
class ProbeVerbsTest {
    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();
    private static final World W = new Sim(CFG).newWorld(List.of("P", "Q"), 7);
    private static final CountryView V = CountryView.of(W, CFG, 0);

    @Test
    void adjacentNamesAllSixDirections() {
        String out = Console.adjacent(V, V.capital());
        for (int d = 0; d < 6; d++) assertThat(out).contains(Hex.dirName(d));
        assertThat(out).contains("around 0,0");
    }

    @Test
    void adjacentTellsYouWhatIsYoursAndWhatIsNot() {
        String out = Console.adjacent(V, V.capital());
        // the sanctuary sits beside the capital, so at least one direction is ours
        assertThat(out).contains("yours:");
        // and something round a two-sector country is not
        assertThat(out.contains("unowned") || out.contains("sea")).as("a frontier of some kind").isTrue();
    }

    @Test
    void adjacentDoesNotInventWhatIsNotInView() {
        // a hex far from anything we own is not in a two-sector country's view at all
        Coord far = new Coord((V.capital().x() + 7) % V.width(), (V.capital().y() + 5) % V.height());
        String out = Console.adjacent(V, far);
        assertThat(out).contains("unknown");
    }

    @Test
    void theDeliverDryRunSaysWhetherAnOrderWouldWorkAndWritesNothing() {
        Coord cap = V.capital();
        int ours = -1, notOurs = -1;
        for (int d = 0; d < 6; d++) {
            Coord n = Hex.normalise(W, Hex.stepRaw(cap, d));
            if (n == null) continue;
            var s = W.sector(n);
            if (s.owner() == 0 && s.terrain().isLand()) { if (ours < 0) ours = d; }
            else if (notOurs < 0) notOurs = d;
        }
        assertThat(ours).isGreaterThanOrEqualTo(0);
        assertThat(Console.probeDeliver(V, cap, ours, "food")).contains("would deliver").contains("Nothing was written");
        if (notOurs >= 0)
            assertThat(Console.probeDeliver(V, cap, notOurs, "food")).contains("REFUSED").contains("Nothing was written");
    }

    @Test
    void theDryRunWarnsWhenAnOrderWouldReplaceOne() {
        Coord cap = V.capital();
        int ours = -1;
        for (int d = 0; d < 6; d++) {
            Coord n = Hex.normalise(W, Hex.stepRaw(cap, d));
            if (n != null && W.sector(n).owner() == 0 && W.sector(n).terrain().isLand()) { ours = d; break; }
        }
        World withOrder = new org.hastingtx.empire.engine.command.CommandExecutor(CFG)
                .execute(W, 0, new org.hastingtx.empire.engine.command.Command.Deliver(cap, "food", ours, 50)).world();
        CountryView v2 = CountryView.of(withOrder, CFG, 0);
        assertThat(Console.probeDeliver(v2, cap, ours, "food")).contains("already delivers food").contains("would replace it");
    }
}
