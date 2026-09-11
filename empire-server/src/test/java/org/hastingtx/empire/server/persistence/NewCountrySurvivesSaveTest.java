package org.hastingtx.empire.server.persistence;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #113. A country added to a running game reaches the database through saveDiff, which used to
 * write countries with a bare UPDATE — so a country that did not exist yet updated zero rows and was
 * silently discarded. In memory everything looked right; the country vanished at the next restart.
 * This loads the world back from the database, which is the only place that bug was visible.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class NewCountrySurvivesSaveTest {
    @Autowired WorldRepository worlds;
    @Autowired GameRepository games;
    @Autowired JdbcTemplate jdbc;
    private Long gameId;

    @AfterEach
    void cleanup() {
        if (gameId != null) jdbc.update("DELETE FROM game WHERE id = ?", gameId);
        jdbc.update("DELETE FROM account WHERE email LIKE 'seat-keep-%@example.invalid'");
    }

    @Test
    void aCountryAddedToARunningGameIsStillThereWhenTheWorldIsLoadedBack() {
        ConfigLoader.Loaded l = new ConfigLoader().loadPreset("teaching");
        GameConfig cfg = l.config();
        Commodities com = Commodities.of(cfg);
        World start = new WorldGenerator(cfg).generate(List.of("A", "B"), 11);

        gameId = games.create("add-country-test", "teaching", new ConfigLoader().toYaml(l.raw()), l.hash(), 11,
                start.width(), start.height(), start.wrapX(), start.wrapY(), null);
        worlds.saveAll(gameId, start, com);

        World after = new WorldGenerator(cfg).addCountry(start, "Carol", 11);
        assertThat(after.countries()).hasSize(3);

        worlds.saveDiff(gameId, start, after, com);

        // the row itself
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM country WHERE game_id = ?", Integer.class, gameId);
        assertThat(rows).as("countries persisted").isEqualTo(3);

        // and the world as the server would rebuild it at startup
        World reloaded = worlds.load(games.find(gameId).orElseThrow(), cfg);
        assertThat(reloaded.countries()).hasSize(3);
        assertThat(reloaded.country(2).name()).isEqualTo("Carol");
        assertThat(reloaded.country(2).capital()).isEqualTo(after.country(2).capital());
        assertThat(reloaded.sector(reloaded.country(2).capital()).owner()).isEqualTo(2);
        assertThat(reloaded.sector(reloaded.country(2).capital()).designation()).isEqualTo("capital");
        assertThat(reloaded.sector(reloaded.country(2).capital()).stock().total())
                .as("the capital's starting stock survived too").isGreaterThan(0.0);
    }

    @Test
    void anExistingSeatKeepsItsOwnerWhenCountriesAreRewritten() {
        ConfigLoader.Loaded l = new ConfigLoader().loadPreset("teaching");
        GameConfig cfg = l.config();
        Commodities com = Commodities.of(cfg);
        World start = new WorldGenerator(cfg).generate(List.of("A", "B"), 11);
        gameId = games.create("seat-keep-test", "teaching", new ConfigLoader().toYaml(l.raw()), l.hash(), 11,
                start.width(), start.height(), start.wrapX(), start.wrapY(), null);
        worlds.saveAll(gameId, start, com);

        // its own account, so the test does not depend on the database already having one
        Long accountId = jdbc.queryForObject(
                "INSERT INTO account (email, name, is_admin) VALUES (?,?,false) RETURNING id",
                Long.class, "seat-keep-" + gameId + "@example.invalid", "seat keeper");
        assertThat(accountId).isNotNull();
        assertThat(games.bind(gameId, 0, accountId)).isEqualTo(1);

        // the upsert's conflict branch must not clobber controller or account_id
        worlds.saveDiff(gameId, start, new WorldGenerator(cfg).addCountry(start, "Carol", 11), com);

        List<GameRepository.Seat> seats = games.seats(gameId);
        assertThat(seats).hasSize(3);
        assertThat(seats.get(0).accountId()).as("country 0 kept its player").isEqualTo(accountId);
        assertThat(seats.get(0).controller()).isEqualTo("human");
        assertThat(seats.get(2).accountId()).as("the new seat is open").isNull();
    }
}
