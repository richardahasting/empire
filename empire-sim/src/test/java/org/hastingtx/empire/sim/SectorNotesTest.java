package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #49: every owned sector can tell what happened to it last update, in step order. */
class SectorNotesTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Coord CAP = TestWorlds.CENTER;

    @Test
    void anAgribusinessTellsItsStoryInOrder() {
        Coord a = Hex.stepRaw(CAP, 0, 1);
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 1000.0));
        w = TestWorlds.own(w, CFG, a, "agribusiness", 100, 127, Map.of("civ", 300.0, "food", 100.0), Map.of("food", 50.0));   // surplus food goes to the capital
        UpdateResult r = Update.run(w, CFG, 9);
        List<String> notes = r.notes().get(a.x() + "," + a.y());
        assertThat(notes).isNotNull().isNotEmpty();
        String all = String.join(" | ", notes);
        assertThat(all).contains("people ate").contains("made").contains("food").contains("sent");
        int ate = indexOf(notes, "people ate"), made = indexOf(notes, "made "), sent = indexOf(notes, "sent ");
        assertThat(ate).isLessThan(made);        // population runs before production
        assertThat(made).isLessThan(sent);       // production before flows
        assertThat(String.join(" ", r.notes().getOrDefault(CAP.x() + "," + CAP.y(), List.of()))).contains("received");
        // unowned sectors have no story
        assertThat(r.notes().keySet()).allMatch(k -> { String[] xy = k.split(","); return r.next().sector(new Coord(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]))).owned(); });
    }

    @Test
    void theLastLineIsWhatItCameUpShortOf() {
        // a plant with workers but no iron wants iron for production and lcm for its road: both end up in the shortage line
        Coord a = Hex.stepRaw(CAP, 0, 1);
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 1000.0));
        w = TestWorlds.own(w, CFG, a, "light_manufacturing", 100, 127, Map.of("civ", 300.0, "food", 500.0), Map.of());
        w = w.withSector(w.sector(a).withRoadTarget(100));
        List<String> notes = Update.run(w, CFG, 9).notes().get(a.x() + "," + a.y());
        assertThat(notes).isNotEmpty();
        String last = notes.get(notes.size() - 1);
        assertThat(last).startsWith("shortage:").contains("iron").contains("lcm");
        assertThat(notes.subList(0, notes.size() - 1)).noneMatch(l -> l.startsWith("shortage:"));
    }

    private static int indexOf(List<String> notes, String prefix) {
        for (int i = 0; i < notes.size(); i++) if (notes.get(i).startsWith(prefix)) return i;
        return -1;
    }
}
