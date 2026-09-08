package org.hastingtx.empire.server.console;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.engine.view.CountryView.SectorView;
import org.hastingtx.empire.sim.Sim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue #38: one token names one sector or many. */
class SectorSelectorTest {
    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();
    private static final World W = new Sim(CFG).newWorld(List.of("P", "Q"), 7);
    private static final CountryView V = CountryView.of(W, CFG, 0);
    private static final List<SectorView> MINE = V.sectors().stream().filter(SectorView::full).toList();

    @Test
    void oneCoordinateIsStillOneSector() {
        assertThat(SectorSelector.isMass("0,0")).isFalse();
        assertThat(SectorSelector.expand(V, CFG, "0,0")).containsExactly(V.capital());
    }

    @Test
    void starIsEveryOwnedSector() {
        List<Coord> all = SectorSelector.expand(V, CFG, "*");
        assertThat(all).hasSize(MINE.size()).containsExactlyInAnyOrderElementsOf(MINE.stream().map(SectorView::at).toList());
        assertThat(MINE.size()).isGreaterThan(1);
    }

    @Test
    void starTypeByIdOrGlyphIsOneDesignation() {
        String glyph = CFG.sectorType("capital").glyph();
        assertThat(SectorSelector.expand(V, CFG, "*:capital")).containsExactly(V.capital());
        assertThat(SectorSelector.expand(V, CFG, "*:" + glyph)).containsExactly(V.capital());
        assertThatThrownBy(() -> SectorSelector.expand(V, CFG, "*:nope")).hasMessageContaining("unknown designation");
    }

    @Test
    void rectangleIsInclusiveAndOrderInsensitive() {
        assertThat(SectorSelector.expand(V, CFG, "0:0,0:0")).containsExactly(V.capital());
        List<Coord> box = SectorSelector.expand(V, CFG, "1:-1,1:-1");
        assertThat(box).contains(V.capital());
        for (Coord c : box) {
            SectorView s = MINE.stream().filter(x -> x.at().equals(c)).findFirst().orElseThrow();
            assertThat(Math.abs(s.relative().x())).isLessThanOrEqualTo(1);
            assertThat(Math.abs(s.relative().y())).isLessThanOrEqualTo(1);
        }
        assertThatThrownBy(() -> SectorSelector.expand(V, CFG, "1:2")).hasMessageContaining("x1:x2,y1:y2");
        assertThatThrownBy(() -> SectorSelector.expand(V, CFG, "50:60,50:60")).hasMessageContaining("none of your sectors");
    }
}
