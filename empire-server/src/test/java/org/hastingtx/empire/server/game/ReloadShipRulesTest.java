package org.hastingtx.empire.server.game;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.persistence.GameRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Richard 2026-09-14: game 82 could not build a tender because its rules predate tenders, and a full
 * reload would also have taken research-scaled population ceilings and cost ~32,000 people. Reload ship
 * rules takes {@code units.ships} from the preset and leaves everything else as the game has it.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class ReloadShipRulesTest {
    @Autowired GameService games;
    @Autowired GameRepository repo;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> made = new ArrayList<>(), accounts = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (long id : made) jdbc.update("DELETE FROM game WHERE id = ?", id);
        for (long id : accounts) jdbc.update("DELETE FROM account WHERE id = ?", id);
    }

    private Account account(boolean admin) {
        Long id = jdbc.queryForObject("INSERT INTO account (email, name, is_admin) VALUES (?,?,?) RETURNING id",
                Long.class, "ships-" + System.nanoTime() + "@example.invalid", "who", admin);
        accounts.add(id);
        return new Account(id, "who@example.invalid", "who", admin);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyTheShipRulesChange() {
        GameService.Game g = games.createWithSeats("teaching", "ship-rules-" + System.nanoTime(), 2, 11L, null, WorldOverrides.NONE);
        made.add(g.id);
        // make the stored rules look like an old game's: no tender class, no combat — and a non-ship setting
        // that differs from the preset, which must survive
        ConfigLoader loader = new ConfigLoader();
        Map<String, Object> raw = loader.loadYaml(repo.find(g.id).orElseThrow().configYaml()).raw();
        Map<String, Object> ships = (Map<String, Object>) ((Map<String, Object>) raw.get("units")).get("ships");
        ships.remove("combat");
        ((List<Object>) ships.get("classes")).removeIf(c -> "tender".equals(((Map<String, Object>) c).get("id")));
        Map<String, Object> pop = (Map<String, Object>) ((Map<String, Object>) raw.get("economy")).get("population");
        pop.put("max_pop_research_curve", Map.of("type", "res_pop"));        // teaching ships "none"
        String old = loader.toYaml(raw);
        repo.setConfig(g.id, old, loader.loadYaml(old).hash());
        games.refreshShipRules(g.id, account(true));            // reads the stored rules, not the game in memory

        var cfg = games.get(g.id).cfg;
        assertThat(cfg.units().ships().hasClass("tender")).as("tenders came in").isTrue();
        assertThat(cfg.units().ships().combat()).as("and combat").isNotNull();
        assertThat(cfg.economy().population().maxPopResearchCurve().type()).as("the economy was left alone").isEqualTo("res_pop");
        assertThat(games.get(g.id).world.countries()).as("the world reloaded").isNotEmpty();

        assertThatThrownBy(() -> games.refreshShipRules(g.id, account(false))).isInstanceOf(SecurityException.class);
    }
}
