package org.hastingtx.empire.server.game;

import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.persistence.GameRepository;
import org.hastingtx.empire.server.persistence.WorldRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Issue #128: POGO exists in every game, is never a seat, and its edits reach the database. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class DeityPowersTest {
    @Autowired GameService games;
    @Autowired GameRepository repo;
    @Autowired WorldRepository worlds;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> made = new ArrayList<>();
    private final List<Long> accounts = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (long id : made) jdbc.update("DELETE FROM game WHERE id = ?", id);
        for (long id : accounts) jdbc.update("DELETE FROM account WHERE id = ?", id);
    }

    private Account account(String who, boolean admin) {
        Long id = jdbc.queryForObject("INSERT INTO account (email, name, is_admin) VALUES (?,?,?) RETURNING id",
                Long.class, who + "-" + System.nanoTime() + "@example.invalid", who, admin);
        accounts.add(id);
        return new Account(id, who + "@example.invalid", who, admin);
    }

    private GameService.Game newGame(int seats) {
        GameService.Game g = games.createWithSeats("teaching", "pogo-test-" + System.nanoTime(), seats, 11L, null, WorldOverrides.NONE);
        made.add(g.id);
        return g;
    }

    @Test
    void everyNewGameHasADeityAtTheOrigin() {
        GameService.Game g = newGame(2);
        int id = games.deityCountry(g.id);
        assertThat(g.world.country(id).name()).isEqualTo(GameService.DEITY);
        assertThat(g.world.country(id).capital()).isEqualTo(new Coord(0, 0));
    }

    @Test
    void theDeityIsNotASeatAndDoesNotHoldUpTheBell() {
        GameService.Game g = newGame(2);
        assertThat(games.openSeats(g.id)).as("two seats, not three").isEqualTo(2);
        assertThat(games.summary(g, null).countries()).hasSize(2);
        assertThat(games.summary(g, null).countries()).noneSatisfy(c -> assertThat(c.name()).isEqualTo(GameService.DEITY));

        games.join(g.id, account("a", false), 0, "Ruritania");
        games.join(g.id, account("b", false), 1, "Freedonia");
        assertThat(g.status).as("the deity must not keep the game in setup forever").isEqualTo("running");
    }

    @Test
    void theDeitySeesTheWholeMap() {
        GameService.Game g = newGame(2);
        var view = games.deityView(g.id, account("deity", true));
        assertThat(view.sectors()).hasSize(g.world.width() * g.world.height());
    }

    @Test
    void onlyADeityMay() {
        GameService.Game g = newGame(2);
        Account mortal = account("mortal", false);
        assertThatThrownBy(() -> games.deityView(g.id, mortal)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> games.editCountry(g.id, 0, new GameService.CountryEdit(1.0, null, null, null, null, null, null, null), mortal))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void aSectorEditReachesTheDatabaseAndIsLogged() {
        GameService.Game g = newGame(2);
        Account deity = account("deity", true);
        Coord at = g.world.country(0).capital();

        games.editSector(g.id, at.x(), at.y(),
                new GameService.SectorEdit(null, null, 42.0, null, Map.of("food", 1234.0), null, 99, null, null, null, null, null, null), deity);

        World reloaded = worlds.load(repo.find(g.id).orElseThrow(), g.cfg);
        assertThat(reloaded.sector(at).efficiency()).isEqualTo(42.0);
        assertThat(reloaded.sector(at).resources().minerals()).isEqualTo(99);
        assertThat(reloaded.sector(at).stock().get(g.com.index("food"))).isEqualTo(1234.0);

        List<Map<String, Object>> edits = games.deityEdits(g.id);
        assertThat(edits).hasSize(1);
        assertThat(edits.get(0).get("target").toString()).contains(at.x() + "," + at.y());
        assertThat(edits.get(0).get("changes").toString()).contains("efficiency").contains("minerals");
    }

    @Test
    void aCountryEditReachesTheDatabaseAndIsLogged() {
        GameService.Game g = newGame(2);
        Account deity = account("deity", true);

        games.editCountry(g.id, 0, new GameService.CountryEdit(999999.0, null, 75.0, null, null, null, null, null), deity);

        World reloaded = worlds.load(repo.find(g.id).orElseThrow(), g.cfg);
        assertThat(reloaded.country(0).cash()).isEqualTo(999999.0);
        assertThat(reloaded.country(0).levels().tech()).isEqualTo(75.0);
        assertThat(games.deityEdits(g.id)).hasSize(1);
    }

    @Test
    void anEditThatChangesNothingIsRefused() {
        GameService.Game g = newGame(2);
        Account deity = account("deity", true);
        assertThatThrownBy(() -> games.editCountry(g.id, 0, new GameService.CountryEdit(null, null, null, null, null, null, null, null), deity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nothing to change");
    }

    @Test
    void offMapCoordinatesAreRefused() {
        GameService.Game g = newGame(2);
        Account deity = account("deity", true);
        assertThatThrownBy(() -> games.editSector(g.id, 9999, 9999,
                new GameService.SectorEdit(null, null, 1.0, null, null, null, null, null, null, null, null, null, null), deity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("off the map");
    }

    /** The gap this feature exists to close: a bot that lost its one-time token. */
    @Test
    void aBotSeatCanBeGivenAFreshToken() {
        GameService.Game g = newGame(2);
        Account deity = account("deity", true);
        GameService.Seated bot = games.addCountry(g.id, "Grok", "agent", deity);
        assertThat(bot.token()).isNotBlank();

        String replacement = games.reissueToken(g.id, bot.countryId(), deity);
        assertThat(replacement).isNotBlank().isNotEqualTo(bot.token());
        assertThat(repo.seats(g.id)).anySatisfy(s -> {
            if (s.countryId() == bot.countryId()) assertThat(s.controller()).isEqualTo("agent");
        });
    }

    @Test
    void aPersonsSeatCannotBeGivenABotToken() {
        GameService.Game g = newGame(2);
        Account deity = account("deity", true);
        games.join(g.id, account("person", false), 0, "Ruritania");
        assertThatThrownBy(() -> games.reissueToken(g.id, 0, deity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("magic link");
    }
}
