package org.hastingtx.empire.engine.geo;

import org.hastingtx.empire.engine.model.Coord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HexTest {
    @Test
    void offsetCubeRoundTrip() {
        for (int y = -5; y < 12; y++) for (int x = -5; x < 12; x++) {
            Coord c = new Coord(x, y);
            assertThat(Hex.toOffset(Hex.toCube(c))).isEqualTo(c);
        }
    }

    @Test
    void sixStepsAroundReturnHome() {
        Coord home = new Coord(7, 7);
        Coord c = home;
        for (int d = 0; d < 6; d++) c = Hex.stepRaw(c, d);
        assertThat(c).isEqualTo(home);
        assertThat(Hex.distanceRaw(home, Hex.stepRaw(home, 2, 5))).isEqualTo(5);
    }

    @Test
    void rotationBySixIsIdentityAndPreservesDistance() {
        Coord center = new Coord(10, 10), p = new Coord(13, 8);
        assertThat(Hex.rotate(p, center, 6)).isEqualTo(p);
        for (int k = 0; k < 6; k++) assertThat(Hex.distanceRaw(center, Hex.rotate(p, center, k))).isEqualTo(Hex.distanceRaw(center, p));
    }

    @Test
    void rotatingADirectionStepGivesTheNextDirection() {
        Coord center = new Coord(10, 10);
        for (int d = 0; d < 6; d++) {
            Coord stepped = Hex.stepRaw(center, d, 3);
            assertThat(Hex.rotate(stepped, center, 1)).isEqualTo(Hex.stepRaw(center, (d + 1) % 6, 3));
        }
    }

    /**
     * The allocation-free neighbour lookup must agree with the list-building one, everywhere, on both
     * a wrapping world and a bounded one (issue #87). The arithmetic is duplicated for speed, so this
     * is the test that keeps the duplicate honest — including the edges, where a bounded world has
     * fewer than six neighbours and the direction order still has to line up.
     */
    @org.junit.jupiter.api.Test
    void neighbourIndexAgreesWithNeighbours() {
        for (boolean wrap : new boolean[] {true, false}) {
            org.hastingtx.empire.engine.model.World w = world(16, 8, wrap);
            for (int y = 0; y < w.height(); y++) {
                for (int x = 0; x < w.width(); x++) {
                    java.util.List<org.hastingtx.empire.engine.model.Coord> expected =
                            Hex.neighbours(w, new org.hastingtx.empire.engine.model.Coord(x, y));
                    java.util.List<org.hastingtx.empire.engine.model.Coord> actual = new java.util.ArrayList<>();
                    for (int d = 0; d < 6; d++) {
                        int idx = Hex.neighbourIndex(w, x, y, d);
                        if (idx >= 0) actual.add(new org.hastingtx.empire.engine.model.Coord(idx % w.width(), idx / w.width()));
                    }
                    org.assertj.core.api.Assertions.assertThat(actual)
                            .describedAs("wrap=%s at %d,%d", wrap, x, y).isEqualTo(expected);
                }
            }
        }
    }

    private static org.hastingtx.empire.engine.model.World world(int width, int height, boolean wrap) {
        java.util.List<org.hastingtx.empire.engine.model.Sector> secs = new java.util.ArrayList<>();
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                secs.add(org.hastingtx.empire.engine.model.Sector.blank(
                        new org.hastingtx.empire.engine.model.Coord(x, y),
                        org.hastingtx.empire.engine.model.Terrain.PLAINS, 0,
                        org.hastingtx.empire.engine.model.Resources.NONE, 4));
        return new org.hastingtx.empire.engine.model.World(width, height, wrap, wrap, secs,
                java.util.List.of(), java.util.List.of(), 0);
    }
}
