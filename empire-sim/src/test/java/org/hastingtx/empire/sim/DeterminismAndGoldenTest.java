package org.hastingtx.empire.sim;

import org.hastingtx.empire.agents.scripted.ScriptedAgent;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.World;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterminismAndGoldenTest {
    static final List<String> NAMES = List.of("Alpha", "Bravo", "Charlie");
    static final int UPDATES = 20;
    static final long SEED = 20260907L;

    @Test
    void sameSeedSameHashes() {
        GameConfig cfg = TestWorlds.teaching();
        Sim sim = new Sim(cfg);
        Sim.Result a = sim.run(sim.newWorld(NAMES, SEED), UPDATES, SEED, id -> new ScriptedAgent());
        Sim.Result b = sim.run(sim.newWorld(NAMES, SEED), UPDATES, SEED, id -> new ScriptedAgent());
        assertThat(a.hashes()).isEqualTo(b.hashes());
        Sim.Result c = sim.run(sim.newWorld(NAMES, SEED + 1), UPDATES, SEED + 1, id -> new ScriptedAgent());
        assertThat(c.hashes().get(UPDATES - 1)).isNotEqualTo(a.hashes().get(UPDATES - 1));
    }

    /**
     * Golden file: teaching preset, 3 scripted countries, 20 updates. If this fails you changed
     * the rules, the config, or the RNG discipline. Regenerate deliberately with
     * {@code -Dgolden.update=true} and explain why in the commit.
     */
    @Test
    void goldenHash() throws IOException {
        GameConfig cfg = TestWorlds.teaching();
        Sim sim = new Sim(cfg);
        World w = sim.newWorld(NAMES, SEED);
        Sim.Result r = sim.run(w, UPDATES, SEED, id -> new ScriptedAgent());
        String actual = r.hashes().get(UPDATES - 1);
        Path golden = Path.of("src/test/resources/golden/teaching-3-20.sha256");
        if (Boolean.getBoolean("golden.update") || !Files.exists(golden)) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual + "\n");
        }
        assertThat(actual).isEqualTo(Files.readString(golden).strip());
    }
}
