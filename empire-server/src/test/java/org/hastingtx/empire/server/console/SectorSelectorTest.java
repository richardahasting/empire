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
    void mixedSelectionScalesWarehouseThresholdsTenfold() {
        assertThat(SectorSelector.isMixed("*")).isTrue();
        assertThat(SectorSelector.isMixed("-2:2,-2:2")).isTrue();
        assertThat(SectorSelector.isMixed("*:warehouse")).isFalse();
        assertThat(SectorSelector.isMixed("0,0")).isFalse();
        assertThat(CFG.distribution().massThresholdMultiplier("warehouse")).isEqualTo(10.0);
        assertThat(CFG.distribution().massThresholdMultiplier("agribusiness")).isEqualTo(1.0);
        // a view in which the capital's sector is a warehouse
        SectorView cap = MINE.stream().filter(x -> x.at().equals(V.capital())).findFirst().orElseThrow();
        SectorView wh = new SectorView(cap.at(), cap.relative(), true, cap.terrain(), cap.elevation(), cap.owner(), "warehouse", cap.efficiency(), cap.mobility(),
                cap.roadLevel(), cap.roadTarget(), cap.railLevel(), cap.railTarget(), cap.stock(), cap.thresholds(), cap.distCenter(), cap.held(), cap.resources());
        CountryView v2 = new CountryView(V.countryId(), V.name(), V.updateNumber(), V.capital(), V.wrapX(), V.wrapY(), V.cash(), V.btu(), V.levels(), V.handicap(),
                V.inSanctuary(), V.bankrupt(), V.commodityIds(), List.of(wh), V.otherCountryNames());
        assertThat(SectorSelector.massThreshold(v2, CFG, cap.at(), 400)).isEqualTo(4000.0);
        assertThat(SectorSelector.massThreshold(V, CFG, cap.at(), 400)).isEqualTo(400.0);     // the capital is not a warehouse
        assertThat(SectorSelector.massThreshold(v2, CFG, cap.at(), -1)).isEqualTo(-1.0);      // clearing passes through
        assertThat(SectorSelector.massThresholdNote(CFG)).isEqualTo("warehouse ×10");
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
