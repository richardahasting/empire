package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A parcel in transit is picked up whole and put back in pieces; the pieces must add up (issue #97).
 *
 * <p>The failure needs a <em>fractional</em> claim, which is why nothing caught it. Current config
 * quantises flows to whole units, so the claim is whole and the arithmetic is safe by accident. A game
 * created before that config landed keeps its own snapshot and still runs fractional — which is exactly
 * where it bit, in a live game, one civilian at a time.
 *
 * <p>So this fixture deliberately turns the quantum off. That is not an exotic setting: it is what every
 * game created before #77 is playing by, for life.
 */
class ParcelSplitTest {

    /** The teaching preset with flow quantisation off — a pre-#77 game's rules. */
    private static GameConfig unquantised() {
        ConfigLoader ldr = new ConfigLoader();
        ConfigLoader.Loaded l = ldr.loadPreset("teaching");
        Map<String, Object> raw = new java.util.LinkedHashMap<>(l.raw());
        @SuppressWarnings("unchecked")
        Map<String, Object> dist = new java.util.LinkedHashMap<>((Map<String, Object>) raw.get("distribution"));
        dist.put("quantum", null);
        raw.put("distribution", dist);
        return ldr.loadYaml(ldr.toYaml(raw)).config();
    }

    /**
     * Play an unquantised game and let it make its own parcels. This is how the bug actually surfaced:
     * not from a hand-built fixture but from ordinary distribution running out of mobility mid-route,
     * over and over, until one split landed on a fraction.
     */
    @Test
    void anUnquantisedGamePlaysWithoutLosingAnybody() {
        GameConfig cfg = unquantised();
        assertThat(cfg.distribution().quantumOr0()).describedAs("the fixture really is unquantised").isZero();
        Sim sim = new Sim(cfg);
        for (long seed : new long[] {11L, 4242L, 20260910L}) {
            World w = sim.newWorld(List.of("A", "B"), seed);
            final World start = w;
            // Update.run throws on a violated invariant; that is the detector, and it is what paused a
            // live game with "conservation violated for civ ... (out by -1)".
            org.assertj.core.api.Assertions.assertThatCode(() -> sim.run(start, 25, seed, id -> new org.hastingtx.empire.agents.scripted.ScriptedAgent()))
                    .describedAs("seed %d: a parcel split at a fractional boundary must not lose a unit", seed)
                    .doesNotThrowAnyException();
        }
    }

    private static long totalCiv(World w, int civ) {
        long n = 0;
        for (Sector s : w.sectors()) {
            n += (long) s.stock().get(civ);
            for (HeldParcel p : s.held()) if (p.commodity() == civ) n += (long) p.qty();
        }
        for (Ship sh : w.ships()) { n += (long) sh.stock().get(civ); n += (long) sh.crew(); }
        return n;
    }
}
