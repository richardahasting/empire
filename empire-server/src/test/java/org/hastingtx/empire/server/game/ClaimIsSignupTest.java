package org.hastingtx.empire.server.game;

import org.hastingtx.empire.server.auth.AuthService;
import org.hastingtx.empire.server.persistence.GameRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Issue #126: claiming a country is the sign-up. The seat is held, not taken, until the magic link
 * is clicked — which is what stops a stranger with invented addresses taking every seat and, because
 * the bell fires on the last one (#123), starting somebody else's game.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class ClaimIsSignupTest {
    @Autowired GameService games;
    @Autowired GameRepository repo;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> made = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (long id : made) jdbc.update("DELETE FROM game WHERE id = ?", id);
        jdbc.update("DELETE FROM account WHERE email LIKE '%@claimtest.invalid'");
    }

    private GameService.Game newGame(int seats) {
        GameService.Game g = games.createWithSeats("teaching", "claim-test-" + System.nanoTime(), seats, 11L, null, WorldOverrides.NONE);
        made.add(g.id);
        return g;
    }

    private String email(String who) { return who + "-" + System.nanoTime() + "@claimtest.invalid"; }

    /** The link is what proves the email, so it is what turns a hold into a seat. */
    @Test
    void aClaimIsAHoldUntilTheLinkIsClicked() {
        GameService.Game g = newGame(2);
        String e = email("ann");

        var claimed = games.claim(g.id, 0, "Ruritania", e, "Ann");
        assertThat(claimed.seat()).isEqualTo("emp1");
        assertThat(claimed.name()).isEqualTo("Ruritania");

        // held, not taken: no account on the seat, and the country is still emp1
        var seat = repo.seats(g.id).get(0);
        assertThat(seat.accountId()).as("not seated until confirmed").isNull();
        assertThat(seat.reserved(Instant.now())).isTrue();
        assertThat(seat.name()).as("the chosen name waits").isEqualTo("emp1");
        assertThat(seat.pendingName()).isEqualTo("Ruritania");
        assertThat(g.world.country(0).name()).isEqualTo("emp1");

        long accountId = jdbc.queryForObject("SELECT id FROM account WHERE email = ?", Long.class, e);
        games.confirmReservations(accountId);

        var after = repo.seats(g.id).get(0);
        assertThat(after.accountId()).isEqualTo(accountId);
        assertThat(after.controller()).isEqualTo("human");
        assertThat(after.name()).isEqualTo("Ruritania");
        assertThat(g.world.country(0).name()).as("and reaches the world, not just the row").isEqualTo("Ruritania");
    }

    /** The squat this whole design exists to prevent. */
    @Test
    void aHeldSeatDoesNotCountAsFilledAndCannotRingTheBell() {
        GameService.Game g = newGame(2);
        games.claim(g.id, 0, "Ruritania", email("ann"), "Ann");
        games.claim(g.id, 1, "Freedonia", email("bob"), "Bob");

        assertThat(games.openSeats(g.id)).as("both held, neither confirmed").isZero();
        assertThat(g.status).as("a game must not start on unproved emails").isEqualTo("setup");
    }

    @Test
    void confirmingTheLastSeatRingsTheBell() {
        GameService.Game g = newGame(2);
        String a = email("ann"), b = email("bob");
        games.claim(g.id, 0, "Ruritania", a, "Ann");
        games.claim(g.id, 1, "Freedonia", b, "Bob");

        games.confirmReservations(jdbc.queryForObject("SELECT id FROM account WHERE email = ?", Long.class, a));
        assertThat(g.status).as("one confirmed, one still only held").isEqualTo("setup");

        games.confirmReservations(jdbc.queryForObject("SELECT id FROM account WHERE email = ?", Long.class, b));
        assertThat(g.status).isEqualTo("running");
        assertThat(g.nextUpdateAt).isNotNull();
    }

    @Test
    void nobodyElseCanTakeASeatSomebodyIsClaiming() {
        GameService.Game g = newGame(2);
        games.claim(g.id, 0, "Ruritania", email("ann"), "Ann");
        assertThatThrownBy(() -> games.claim(g.id, 0, "Sylvania", email("bob"), "Bob"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("in the middle of taking");
    }

    @Test
    void aLapsedHoldGivesTheSeatBack() {
        GameService.Game g = newGame(2);
        games.claim(g.id, 0, "Ruritania", email("ann"), "Ann");
        assertThat(games.openSeats(g.id)).isEqualTo(1);

        // wind the hold into the past, as the sweep will find it
        jdbc.update("UPDATE country SET reserved_until = now() - interval '1 hour' WHERE game_id = ? AND country_id = 0", g.id);
        games.releaseStaleReservations();

        assertThat(games.openSeats(g.id)).as("the seat comes back").isEqualTo(2);
        var seat = repo.seats(g.id).get(0);
        assertThat(seat.reservedBy()).isNull();
        assertThat(seat.pendingName()).as("and leaves no trace").isNull();
        assertThat(seat.name()).isEqualTo("emp1");
    }

    @Test
    void aPendingNameStillHasToBeUnique() {
        GameService.Game g = newGame(3);
        games.claim(g.id, 0, "Ruritania", email("ann"), "Ann");
        assertThatThrownBy(() -> games.claim(g.id, 1, "ruritania", email("bob"), "Bob"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already a country");
    }

    @Test
    void aClaimNeedsANameAndARealSeat() {
        GameService.Game g = newGame(2);
        assertThatThrownBy(() -> games.claim(g.id, 0, "  ", email("ann"), "Ann"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> games.claim(g.id, 99, "Ruritania", email("ann"), "Ann"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no such seat");
    }

    @Test
    void theDeitysOwnCountryIsNotOnOffer() {
        GameService.Game g = newGame(2);
        int deity = games.deityCountry(g.id);
        assertThatThrownBy(() -> games.claim(g.id, deity, "Ruritania", email("ann"), "Ann"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no such seat");
    }
}
