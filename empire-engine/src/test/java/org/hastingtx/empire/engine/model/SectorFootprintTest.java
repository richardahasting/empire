package org.hastingtx.empire.engine.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a sector weighs (issue #77). The sizes are computed from the declared fields under the standard
 * HotSpot layout — a 12-byte header, compressed oops, 8-byte alignment — rather than measured, so the
 * test is deterministic and says what it means: these are the field widths the design chose.
 *
 * <p>The point of the issue was the arithmetic at scale. A 512×1024 world is 524,288 sectors; a million
 * is reachable. Every reference field is an object header plus a cache miss, so the test also asserts
 * the shape — four references and no more — not only the byte count.
 */
class SectorFootprintTest {

    private static final int HEADER = 12, REF = 4, ALIGN = 8;

    private static int align(int n) { return (n + ALIGN - 1) / ALIGN * ALIGN; }

    private static int width(Class<?> t) {
        if (t == boolean.class || t == byte.class) return 1;
        if (t == short.class || t == char.class) return 2;
        if (t == int.class || t == float.class) return 4;
        if (t == long.class || t == double.class) return 8;
        return REF;
    }

    private static List<Field> instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers())) out.add(f);
        return out;
    }

    private static int shallow(Class<?> c) {
        int n = HEADER;
        for (Field f : instanceFields(c)) n += width(f.getType());
        return align(n);
    }

    private static int array(Class<?> component, int length) { return align(16 + width(component) * length); }

    @Test
    void aSectorIsTwoCacheLinesAndFourReferences() {
        List<Field> refs = instanceFields(Sector.class).stream().filter(f -> !f.getType().isPrimitive()).toList();
        assertThat(refs).describedAs("reference fields on Sector: each is a header and a cache miss").hasSize(4);
        assertThat(refs.stream().map(f -> f.getType().getSimpleName()))
                .containsExactlyInAnyOrder("int[]", "Stocks", "List", "DeliverOrders");

        // Position, terrain, endowments, tenure and every 0..100 scale live inline. Nothing here is a
        // Coord, a Resources or a String; those cost a pointer plus an object apiece.
        assertThat(shallow(Sector.class)).describedAs("Sector itself").isLessThanOrEqualTo(64);
    }

    @Test
    void anEmptySectorCostsNothingBeyondItself() {
        Sector a = Sector.blank(new Coord(1, 1), Terrain.OCEAN, 0, Resources.NONE, 14);
        Sector b = Sector.blank(new Coord(9, 9), Terrain.WILDERNESS, 0, Resources.NONE, 14);

        // Most of a large world is water and wilderness: no stock, no thresholds, no orders. Those three
        // are immutable and identical, so they are shared rather than copied half a million times.
        assertThat(a.stock()).isSameAs(b.stock());
        assertThat(a.deliver()).isSameAs(b.deliver());
        assertThat(a.held()).isSameAs(b.held());
        assertThat(a.thresholds()).isEqualTo(b.thresholds());
    }

    @Test
    void aFullyLoadedSectorFitsTheBudget() {
        int nCom = 14;
        // int, not short: a warehouse holds 100,000 and a short stops at 32,767 (issue #91).
        int stocks = shallow(Stocks.class) + array(int.class, nCom);
        int thresholds = array(int.class, nCom);
        int deliver = shallow(DeliverOrders.class) + array(byte.class, nCom) + array(int.class, nCom);
        int total = shallow(Sector.class) + stocks + thresholds + deliver;

        // #77 aimed at ~230 bytes for a sector carrying everything it can carry; widening quantities to
        // int for #91 cost 72 of them, which against 610 before #77 is a trade worth making. Held
        // parcels are excluded: a parcel is cargo in transit, not part of the hex.
        assertThat(total).describedAs("a sector with stock, thresholds and delivery orders").isLessThanOrEqualTo(350);

        // And the shape the win actually comes from: the old record kept four more objects per sector —
        // a Coord, a Resources, a Coord for the distribution centre, and boxed 0..100 scales.
        assertThat(shallow(Sector.class) + stocks).describedAs("the common case: stock but nothing else").isLessThanOrEqualTo(160);
    }

    @Test
    void aScaleIsARoundedByteAndAThresholdIsExact() {
        Sector s = Sector.blank(new Coord(0, 0), Terrain.PLAINS, 0, Resources.NONE, 14);

        // Rule 2: half-up. Rule 4: the decimal place of a 0..100 scale is not visible to a player.
        assertThat(s.withEfficiency(40.5).efficiency()).isEqualTo(41);
        assertThat(s.withEfficiency(40.4).efficiency()).isEqualTo(40);
        assertThat(s.withEfficiency(1000).efficiency()).isEqualTo(100);
        assertThat(s.withMobility(-5).mobility()).isEqualTo(0);

        // Rule 5: a threshold is an exact quantity. Byte scaling would make `thresh food 100` mean 120.
        assertThat(s.withThreshold(3, 9999).threshold(3)).isEqualTo(9999);
        assertThat(s.withThreshold(3, 100_000).threshold(3)).describedAs("a warehouse's ceiling, issue #91").isEqualTo(100_000);
        assertThat(s.hasThreshold(3)).isFalse();
        assertThat(s.withThreshold(3, 100).hasThreshold(3)).isTrue();
        assertThat(s.withThreshold(3, 100).withThreshold(3, Double.NaN).hasThreshold(3)).isFalse();
    }

    @Test
    void positionAndDistributionCentreSurviveTheNarrowing() {
        Sector s = Sector.blank(new Coord(2047, 1023), Terrain.PLAINS, 2500, Resources.NONE, 14);
        assertThat(s.at()).isEqualTo(new Coord(2047, 1023));
        assertThat(s.elevation()).isEqualTo(2500);
        assertThat(s.distCenter()).isNull();
        assertThat(s.withDistCenter(new Coord(7, 9)).distCenter()).isEqualTo(new Coord(7, 9));
        assertThat(s.withDistCenter(new Coord(7, 9)).withDistCenter(null).distCenter()).isNull();
    }
}
