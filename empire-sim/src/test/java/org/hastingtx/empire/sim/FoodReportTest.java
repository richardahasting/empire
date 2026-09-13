package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Resources;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.FoodMath;
import org.hastingtx.empire.engine.update.FoodReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #160: basins, deficits, and the deliver that would help. */
class FoodReportTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord FARM = Hex.stepRaw(CAP, 0, 1), RIM = Hex.stepRaw(CAP, 0, 3);

    private static World world() {
        World w = TestWorlds.disc(CFG, 3, Map.of("civ", 100.0, "food", 3000.0));
        // a farm with people on fertile ground gains food; a crowded rim sector on barren ground with none loses it
        w = TestWorlds.own(w, CFG, FARM, "agribusiness", 100, 127, Map.of("civ", 600.0, "food", 200.0), Map.of());
        w = TestWorlds.own(w, CFG, RIM, "wilderness", 100, 127, Map.of("civ", 800.0, "food", 0.0), Map.of());
        Sector rim = w.sector(RIM);
        return w.withSector(rim.withTerrain(rim.terrain(), rim.elevation(), new Resources(0, 0, 0, 0, 0)));
    }

    @Test
    void theRimIsADeficitTheFarmIsABasinAndTheHintPointsFromOneToTheOther() {
        FoodReport.Result r = FoodReport.of(world(), CFG, 0, 5);
        Coord rim = new Coord(3, 0), farm = new Coord(1, 0);
        assertThat(r.deficits()).extracting(FoodReport.Line::relative).contains(rim);
        FoodReport.Line d = r.deficits().stream().filter(l -> l.relative().equals(rim)).findFirst().orElseThrow();
        assertThat(d.starving()).isTrue();
        assertThat(d.eats()).isGreaterThan(0);
        assertThat(r.basins()).extracting(FoodReport.Line::relative).contains(farm);
        FoodReport.Hint h = r.hints().stream().filter(x -> x.deficit().equals(rim)).findFirst().orElseThrow();
        assertThat(h.basin()).isEqualTo(farm);
        assertThat(h.distance()).isEqualTo(2);
        assertThat(h.dir()).as("the first hop from the farm toward the rim is east").isEqualTo(Hex.dirName(0));
    }

    @Test
    void eatingMatchesWhatTheUpdateTakes() {
        World w = world();
        double eats = FoodMath.eatsPerUpdate(CFG, org.hastingtx.empire.engine.model.Commodities.of(CFG), w.sector(FARM));
        var p = CFG.economy().population();
        double limit = p.subsistenceOrNone().limit(w.sector(FARM).fertility());
        assertThat(eats).isCloseTo(Math.max(0, 600 - limit) * p.foodPerCivPerEtu() * CFG.etus(), org.assertj.core.api.Assertions.within(1e-9));
        assertThat(FoodMath.eatsPerUpdate(CFG, 0, 0, 0, 50, true)).isZero();
        assertThat(List.of(r(0).steady())).isNotNull();
    }

    private static FoodReport.Result r(long seed) { return FoodReport.of(world(), CFG, 0, seed); }
}
