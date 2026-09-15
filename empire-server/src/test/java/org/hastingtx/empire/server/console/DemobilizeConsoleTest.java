package org.hastingtx.empire.server.console;

import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.game.GameService;
import org.hastingtx.empire.server.game.WorldOverrides;
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

/** Issue #217: `demob` from the console reaches the world and the database, for one sector or many. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class DemobilizeConsoleTest {
    @Autowired GameService games;
    @Autowired Console console;
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
    void demobStandsSoldiersDownAndItIsSaved() {
        GameService.Game g = games.createWithSeats("teaching", "demob-test-" + System.nanoTime(), 2, 11L, null, WorldOverrides.NONE);
        made.add(g.id);
        Account a = account("a");
        games.join(g.id, a, 0, "Ruritania");
        games.join(g.id, account("b"), 1, "Freedonia");
        Coord cap = g.world.country(0).capital();
        int mil = Commodities.of(g.cfg).mil;
        games.editSector(g.id, cap.x(), cap.y(), new GameService.SectorEdit(null, null, null, null, Map.of("mil", 900.0), null, null, null, null, null, null, null, null), a);

        Console.Reply one = console.run(g.id, a, "demob 0,0 keep 100");
        assertThat(one.accepted()).as(one.output() + " " + one.error()).isTrue();
        assertThat(one.output()).contains("800.0 military stood down");
        assertThat(games.get(g.id).world.sector(cap).stock().get(mil)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT qty FROM sector_stock WHERE game_id = ? AND x = ? AND y = ? AND commodity = 'mil'", Double.class, g.id, cap.x(), cap.y()))
                .as("saved").isEqualTo(100);

        Console.Reply all = console.run(g.id, a, "demob * all");
        assertThat(all.accepted()).as(all.output() + " " + all.error()).isTrue();
        assertThat(games.get(g.id).world.ownedBy(0).stream().mapToDouble(s -> s.stock().get(mil)).sum()).isZero();

        assertThat(console.run(g.id, a, "demob 0,0 lots").accepted()).isFalse();
    }
}
