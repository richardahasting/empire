package org.hastingtx.empire.server.persistence;

import org.hastingtx.empire.agents.scripted.ScriptedAgent;
import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
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
 * Issue #109: deleting a game has to take everything it owns with it. Asserting the game row is gone
 * proves nothing — the question is whether any child table is left holding rows for a game that no
 * longer exists. Runs against the real database (needs EMPIRE_TEST_DB_PASSWORD) and cleans up after itself.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class GameDeleteCascadeTest {
    @Autowired WorldRepository worlds;
    @Autowired GameRepository games;
    @Autowired JdbcTemplate jdbc;
    private Long gameId;

    @AfterEach
    void cleanup() { if (gameId != null) jdbc.update("DELETE FROM game WHERE id = ?", gameId); }

    /** Every table in the schema with a game_id column, asked of the database rather than hard-coded. */
    private List<String> tablesWithGameId() {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.columns " +
                "WHERE column_name = 'game_id' AND table_schema = 'public' ORDER BY table_name",
                String.class);
    }

    private long rowsFor(String table, long id) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE game_id = ?", Long.class, id);
        return n == null ? 0 : n;
    }

    @Test
    void deletingAGameLeavesNothingBehindInAnyTable() {
        ConfigLoader.Loaded l = new ConfigLoader().loadPreset("teaching");
        GameConfig cfg = l.config();
        Sim sim = new Sim(cfg);
        World w0 = sim.newWorld(List.of("A", "B"), 11);
        // play it so the child tables are not empty: sectors, stocks, parcels, orders, logs
        World played = sim.run(w0, 5, 11, id -> new ScriptedAgent()).world();
        Commodities com = Commodities.of(cfg);
        Coord cap = played.country(0).capital();
        played = played.withSeen(List.of(new org.hastingtx.empire.engine.model.SeenSector(
                0, cap, played.sector(cap).terrain(), 0, played.sector(cap).designation(), played.updateNumber())));
        Coord other = played.sectors().stream().filter(x -> x.owner() == 0 && !x.at().equals(cap)).findFirst().orElseThrow().at();
        played = played.withRailLanes(List.of(
                new org.hastingtx.empire.engine.model.RailLane(0, cap, other, List.of(com.index("food")))));

        gameId = games.create("delete-cascade-test", "teaching", new ConfigLoader().toYaml(l.raw()), l.hash(), 11,
                played.width(), played.height(), played.wrapX(), played.wrapY(), null);
        worlds.saveAll(gameId, played, com);

        List<String> tables = tablesWithGameId();
        assertThat(tables).isNotEmpty();
        // the test is only meaningful if the game actually owns rows in more than the game table itself
        long populated = tables.stream().filter(t -> rowsFor(t, gameId) > 0).count();
        assertThat(populated).as("tables holding rows for this game before the delete").isGreaterThan(3L);

        assertThat(games.delete(gameId)).isEqualTo(1);

        assertThat(games.find(gameId)).isEmpty();
        for (String t : tables)
            assertThat(rowsFor(t, gameId)).as("orphaned rows left in %s", t).isZero();

        gameId = null;   // already gone; nothing for cleanup to do
    }

    @Test
    void deletingAGameThatIsNotThereIsNotAnError() {
        assertThat(games.delete(-1L)).isZero();
    }
}
