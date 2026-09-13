package org.hastingtx.empire.server.persistence;

import org.hastingtx.empire.config.ConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #67: a ship's logbook is written with each update and read back newest first, a line at a
 * time, for only as many updates as asked. Runs against the test database (needs EMPIRE_TEST_DB_PASSWORD).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class ShipLogbookTest {
    @Autowired LogRepository logs;
    @Autowired GameRepository games;
    @Autowired JdbcTemplate jdbc;
    private Long gameId;

    @AfterEach
    void cleanup() { if (gameId != null) jdbc.update("DELETE FROM game WHERE id = ?", gameId); }

    @Test
    void aShipsLogbookComesBackNewestFirstALineAtATime() {
        ConfigLoader.Loaded l = new ConfigLoader().loadPreset("teaching");
        gameId = games.create("ship-logbook-test", "teaching", new ConfigLoader().toYaml(l.raw()), l.hash(), 7, 16, 16, false, false, null);
        for (long u = 1; u <= 7; u++) {
            Map<Long, List<String>> ships = u == 4
                    ? Map.of(2L, List.of("holding"))                                          // ship 1 missed an update
                    : Map.of(1L, List.of("loaded " + u + " lcm at 3,4", "sailed 3 hexes to 5,6"), 2L, List.of("in harbour"));
            logs.update(gameId, u, u, null, List.of(), List.of(), 1, Map.of(), ships);
        }

        List<LogRepository.ShipLogEntry> book = logs.shipHistory(gameId, 1, 5);
        assertThat(book).extracting(LogRepository.ShipLogEntry::updateNumber).containsExactly(7L, 6L, 5L, 3L, 2L);
        assertThat(book.get(0).lines()).containsExactly("loaded 7 lcm at 3,4", "sailed 3 hexes to 5,6");
        assertThat(logs.shipHistory(gameId, 2, 50)).hasSize(7);
        assertThat(logs.shipHistory(gameId, 99, 5)).isEmpty();
    }
}
