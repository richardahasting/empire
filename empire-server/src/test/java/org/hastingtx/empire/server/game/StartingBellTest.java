package org.hastingtx.empire.server.game;

import org.hastingtx.empire.engine.config.GameConfig;
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

import static org.assertj.core.api.Assertions.*;

/**
 * Issues #117, #119 and #123: a game is created with N numbered seats, each is claimed and named in
 * one act, and the last claim rings the starting bell. Against the test database, because a rename
 * that only reaches memory is the failure this is really guarding against.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class StartingBellTest {
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

    private Account deity() { return account("bell-deity", true); }

    private Account account(String who, boolean admin) {
        Long id = jdbc.queryForObject("INSERT INTO account (email, name, is_admin) VALUES (?,?,?) RETURNING id",
                Long.class, who + "-" + System.nanoTime() + "@example.invalid", who, admin);
        accounts.add(id);
        return new Account(id, who + "@example.invalid", who, admin);
    }

    private GameService.Game newGame(int seats) {
        GameService.Game g = games.createWithSeats("teaching", "bell-test-" + System.nanoTime(), seats, 11L, null, WorldOverrides.NONE);
        made.add(g.id);
        return g;
    }

    @Test
    void seatsAreNumberedAndTheGameWaits() {
        GameService.Game g = newGame(3);
        assertThat(g.status).as("the bell has not rung").isEqualTo("setup");
        // the world also holds the deity's own country (issue #128); the seats are the players
        assertThat(games.playerSeats(g.id)).extracting(seat -> seat.name()).containsExactly("emp1", "emp2", "emp3");
        assertThat(g.world.countries()).extracting(c -> c.name()).containsExactly("emp1", "emp2", "emp3", GameService.DEITY);
        assertThat(g.nextUpdateAt).as("nothing is scheduled before the bell").isNull();
        assertThat(games.openSeats(g.id)).isEqualTo(3);
    }

    @Test
    void nothingCanBeDoneBeforeTheBell() {
        GameService.Game g = newGame(2);
        Account a = account("early", false);
        games.join(g.id, a, 0, "Ruritania");
        assertThatThrownBy(() -> games.command(g.id, a, new org.hastingtx.empire.engine.command.Command.BreakSanctuary(), "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setup");
    }

    @Test
    void claimingNamesTheSeatAndTheLastClaimRingsTheBell() {
        GameService.Game g = newGame(2);

        games.join(g.id, account("first", false), 0, "Ruritania");
        assertThat(g.world.country(0).name()).isEqualTo("Ruritania");
        assertThat(g.status).as("one seat still open").isEqualTo("setup");
        assertThat(games.openSeats(g.id)).isEqualTo(1);

        games.join(g.id, account("second", false), 1, "Freedonia");
        assertThat(g.status).as("the last seat rang the bell").isEqualTo("running");
        assertThat(g.nextUpdateAt).as("the first update is scheduled").isNotNull();
        assertThat(games.openSeats(g.id)).isZero();
    }

    @Test
    void theDeityCanRingItEarly() {
        GameService.Game g = newGame(3);
        games.join(g.id, account("only", false), 0, "Ruritania");
        assertThat(g.status).isEqualTo("setup");

        games.start(g.id, deity());
        assertThat(g.status).isEqualTo("running");
        assertThat(g.nextUpdateAt).isNotNull();
        assertThat(games.openSeats(g.id)).as("the empty seats stay open").isEqualTo(2);

        assertThatThrownBy(() -> games.start(g.id, deity()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already started");
    }

    @Test
    void aClaimWithoutANameIsRefused() {
        GameService.Game g = newGame(2);
        Account a = account("nameless", false);
        assertThatThrownBy(() -> games.join(g.id, a, 0, "  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThat(g.world.country(0).name()).as("the seat is untouched").isEqualTo("emp1");
        assertThat(games.openSeats(g.id)).as("and unclaimed").isEqualTo(2);
    }

    @Test
    void twoCountriesCannotShareAName() {
        GameService.Game g = newGame(3);
        games.join(g.id, account("one", false), 0, "Ruritania");
        Account b = account("two", false);
        assertThatThrownBy(() -> games.join(g.id, b, 1, "ruritania"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already a country");
    }

    /** The failure this test exists for: a rename that lives only in memory and dies at restart. */
    @Test
    void aRenameSurvivesReloadingTheWorld() {
        GameService.Game g = newGame(2);
        Account a = account("renamer", false);
        games.join(g.id, a, 0, "Ruritania");
        games.renameMine(g.id, a, "Sylvania");
        assertThat(g.world.country(0).name()).isEqualTo("Sylvania");

        GameConfig cfg = g.cfg;
        World reloaded = worlds.load(repo.find(g.id).orElseThrow(), cfg);
        assertThat(reloaded.country(0).name()).as("as the server would rebuild it at startup").isEqualTo("Sylvania");
        assertThat(repo.seats(g.id).get(0).name()).isEqualTo("Sylvania");
    }

    @Test
    void claimingASeatDoesNotDisturbWhoAlreadyHasOne() {
        GameService.Game g = newGame(3);
        Account first = account("keeps", false);
        games.join(g.id, first, 0, "Ruritania");
        games.join(g.id, account("joins", false), 1, "Freedonia");

        List<GameRepository.Seat> seats = repo.seats(g.id);
        assertThat(seats.get(0).accountId()).isEqualTo(first.id());
        assertThat(seats.get(0).controller()).isEqualTo("human");
        assertThat(seats.get(2).accountId()).as("the third is still open").isNull();
    }
}
