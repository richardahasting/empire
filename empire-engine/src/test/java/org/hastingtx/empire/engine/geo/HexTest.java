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
}
