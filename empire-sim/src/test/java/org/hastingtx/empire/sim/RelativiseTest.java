package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.view.CountryView;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Since issue #271 every ship, unit and plane note is relativised while the view is built, and notes carry names
 * players chose. A name like "99999999999,1" used to overflow parseInt there and take the whole view down.
 */
class RelativiseTest {
    private static final GameConfig CFG = TestWorlds.teaching();

    @Test
    void namesThatLookLikeHugeCoordinatesAreLeftAlone() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0));
        Coord cap = w.country(0).capital();
        String hostile = "stopped by 99999999999,1's blockade at " + cap.x() + "," + cap.y() + "; 1,99999999999 and 123456789012345678901234567890,5";
        String out = CountryView.relativise(w, cap, hostile);
        assertThat(out).startsWith("stopped by 99999999999,1's blockade at 0,0;").endsWith("1,99999999999 and 123456789012345678901234567890,5");
    }
}
