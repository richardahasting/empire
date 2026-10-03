package org.hastingtx.empire.server.console;

import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.update.Event;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.game.GameService;
import org.hastingtx.empire.server.game.WorldOverrides;
import org.hastingtx.empire.server.persistence.LogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #268: in game 82 sectors vanished between hourly looks "with no combat" — the update log shows partisans took
 * each one, but a sector that is no longer yours never showed you its note. The census reads the log instead.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class CensusLossesTest {
    @Autowired GameService games;
    @Autowired Console console;
    @Autowired LogRepository logs;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> made = new ArrayList<>();
    private final List<Long> accounts = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (long id : made) jdbc.update("DELETE FROM game WHERE id = ?", id);
        for (long id : accounts) jdbc.update("DELETE FROM account WHERE id = ?", id);
    }

    private Account account(String who) {
        Long id = jdbc.queryForObject("INSERT INTO account (email, name, is_admin) VALUES (?,?,true) RETURNING id",
                Long.class, who + "-" + System.nanoTime() + "@example.invalid", who);
        accounts.add(id);
        return new Account(id, who + "@example.invalid", who, true);
    }

    @Test
    void theCensusNamesWhatPartisansTookFromYouLately() {
        GameService.Game g = games.createWithSeats("teaching", "losses-test-" + System.nanoTime(), 2, 11L, null, WorldOverrides.NONE);
        made.add(g.id);
        Account a = account("a");
        games.join(g.id, a, 0, "Ruritania");
        games.join(g.id, account("b"), 1, "Freedonia");
        Coord cap = g.world.country(0).capital();
        Coord lost = org.hastingtx.empire.engine.geo.Hex.normalise(g.world, new Coord(cap.x() + 2, cap.y()));
        logs.update(g.id, 5, 1, null, List.of(new Event("partisans", 0, lost, "partisans take over " + lost.x() + "," + lost.y(), 300),
                new Event("partisans", 1, lost, "partisans take over 1,1", 300), new Event("starvation", 0, lost, "people starve", 1)), List.of(), 1, Map.of(), Map.of());
        logs.update(g.id, 6, 1, null, List.of(), List.of(), 1, Map.of(), Map.of());

        Coord r = CountryView.relative(g.world, cap, lost);
        String census = console.run(g.id, a, "census").output();
        assertThat(census).contains("updates: u5 partisans take over " + r.x() + "," + r.y() + " — guerrillas grow where there are no soldiers");
        assertThat(census).as("someone else's loss, and other kinds of event, are not yours to read here").doesNotContain("1,1").doesNotContain("starve");

        logs.update(g.id, 5 + Console.LOSS_WINDOW + 1, 1, null, List.of(), List.of(), 1, Map.of(), Map.of());
        assertThat(console.run(g.id, a, "census").output()).as("out of the window").doesNotContain("partisans take over");
    }
}
