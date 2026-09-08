package org.hastingtx.empire.server.persistence;

import org.hastingtx.empire.agents.scripted.ScriptedAgent;
import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.steps.ApplyStep;
import org.hastingtx.empire.sim.Sim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Save a played world, load it back, and the state hash must be identical. Runs against
 * the real database (needs EMPIRE_DB_PASSWORD in the environment) and cleans up its game.
 */
@SpringBootTest(properties = "empire.mail-mode=log")
@EnabledIfEnvironmentVariable(named = "EMPIRE_DB_PASSWORD", matches = ".+")
class WorldRepositoryRoundTripTest {
    @Autowired WorldRepository worlds;
    @Autowired GameRepository games;
    @Autowired JdbcTemplate jdbc;
    private Long gameId;

    @AfterEach
    void cleanup() { if (gameId != null) jdbc.update("DELETE FROM game WHERE id = ?", gameId); }

    @Test
    void savedWorldLoadsByteIdentical() {
        ConfigLoader.Loaded l = new ConfigLoader().loadPreset("teaching");
        GameConfig cfg = l.config();
        Sim sim = new Sim(cfg);
        World w0 = sim.newWorld(List.of("A", "B"), 11);
        World played = sim.run(w0, 5, 11, id -> new ScriptedAgent()).world();   // parcels, thresholds, dist centres, moves exist by now
        Commodities com = Commodities.of(cfg);

        gameId = games.create("roundtrip-test", "teaching", new ConfigLoader().toYaml(l.raw()), l.hash(), 11, played.width(), played.height(), played.wrapX(), played.wrapY(), null);
        worlds.saveAll(gameId, played, com);
        World loaded = worlds.load(games.find(gameId).orElseThrow(), cfg);
        assertThat(ApplyStep.hash(loaded)).isEqualTo(ApplyStep.hash(played));
        assertThat(loaded.pendingMoves()).isEqualTo(played.pendingMoves());

        // diff save: one more update, only changed rows written, still identical
        World next = sim.run(loaded, 1, 12, id -> new ScriptedAgent()).world();
        worlds.saveDiff(gameId, loaded, next, com);
        World loaded2 = worlds.load(games.find(gameId).orElseThrow(), cfg);
        assertThat(ApplyStep.hash(loaded2)).isEqualTo(ApplyStep.hash(next));
        long held = loaded2.sectors().stream().mapToLong(s -> s.held().size()).sum();
        assertThat(held).isEqualTo(next.sectors().stream().mapToLong(Sector::heldCount).sum());
    }
}
