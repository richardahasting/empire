package org.hastingtx.empire.server.console;

import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.view.CountryView;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issues #272 and #273: a trailing {@code check} used to be ignored, so {@code des 7,-4 agribusiness check} really
 * redesignated a mine and {@code expl … check} really settled civilians. Now it is a dry run — the executor's own
 * answer, nothing saved or charged — and words a verb does not read are refused rather than ignored.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class CheckConsoleTest {
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

    private GameService.Game game(Account a) {
        GameService.Game g = games.createWithSeats("teaching", "check-test-" + System.nanoTime(), 2, 11L, null, WorldOverrides.NONE);
        made.add(g.id);
        games.join(g.id, a, 0, "Ruritania");
        games.join(g.id, account("b"), 1, "Freedonia");
        return g;
    }

    private static String rel(World w, Coord abs) {
        Coord r = CountryView.relative(w, w.country(0).capital(), abs);
        return r.x() + "," + r.y();
    }

    @Test
    void checkDescribesAndChangesNothing() {
        Account a = account("a");
        GameService.Game g = game(a);
        World before = games.get(g.id).world;
        Sector some = before.ownedBy(0).stream().filter(s -> !s.at().equals(before.country(0).capital())).findFirst().orElseThrow();
        String at = rel(before, some.at());
        int food = Commodities.of(g.cfg).index("food");

        Console.Reply thresh = console.run(g.id, a, "thresh " + at + " food 2000 check");
        assertThat(thresh.accepted()).as(thresh.error()).isTrue();
        assertThat(thresh.output()).contains("CHECK — nothing was done").contains(at + ": threshold food").contains("→ 2000");

        String other = some.designation().equals("agribusiness") ? "park" : "agribusiness";
        Console.Reply des = console.run(g.id, a, "des " + at + " " + other + " CHECK");
        assertThat(des.accepted()).as(des.error()).isTrue();
        assertThat(des.output()).contains("now " + other).contains(some.designation() + " → " + other);

        Console.Reply mass = console.run(g.id, a, "thresh * food 500 check");
        assertThat(mass.output()).contains("CHECK").contains("applied");

        World after = games.get(g.id).world;
        assertThat(after.sector(some.at()).designation()).isEqualTo(some.designation());
        assertThat(after.sector(some.at()).threshold(food)).isEqualTo(some.threshold(food), org.assertj.core.data.Offset.offset(0.0));
        assertThat(after.country(0).btu()).as("nothing was charged").isEqualTo(before.country(0).btu());
        assertThat(after).as("not a thing changed").isSameAs(before);
    }

    @Test
    void checkOnAnExploreSettlesNobody() {
        Account a = account("a");
        GameService.Game g = game(a);
        assertThat(console.run(g.id, a, "break").accepted()).as("out of sanctuary, so explore may go").isTrue();
        World before = games.get(g.id).world;
        Coord from = null, to = null;
        for (Sector s : before.ownedBy(0)) {
            for (Coord n : Hex.neighbours(before, s.at())) {
                Sector t = before.sector(n);
                if (t.owner() == Sector.NOBODY && t.terrain().isLand()) { from = s.at(); to = n; break; }
            }
            if (to != null) break;
        }
        assertThat(to).as("the teaching map has wilderness next to the capital's land").isNotNull();

        Console.Reply r = console.run(g.id, a, "expl " + rel(before, from) + " " + rel(before, to) + " 10 check");
        assertThat(r.accepted()).as(r.error()).isTrue();
        assertThat(r.output()).contains("CHECK").contains(rel(before, to) + ": becomes yours");
        assertThat(r.output()).as("fertility stays hidden until somebody settles there; a free check is not a free survey").doesNotContain("fertility");
        assertThat(games.get(g.id).world.sector(to).owner()).as("nobody settled").isEqualTo(Sector.NOBODY);
    }

    @Test
    void aFightCannotBePreviewed() {
        Account a = account("a");
        GameService.Game g = game(a);
        World before = games.get(g.id).world;
        Console.Reply r = console.run(g.id, a, "attack 3,3 10 from 0,0 check");
        assertThat(r.accepted()).isFalse();
        assertThat(r.error()).contains("cannot preview attack").contains("nothing was done");
        assertThat(console.run(g.id, a, "sail 1 0,1 check").error()).contains("cannot preview sail");
        Console.Reply said = console.run(g.id, a, "announce we will check");
        assertThat(said.accepted()).as("in a message, check is just a word: " + said.error()).isTrue();
    }

    @Test
    void wordsAVerbDoesNotReadAreRefused() {
        Account a = account("a");
        GameService.Game g = game(a);
        World before = games.get(g.id).world;
        Console.Reply r = console.run(g.id, a, "expl 0,0 1,0 10 please");
        assertThat(r.accepted()).isFalse();
        assertThat(r.error()).contains("did not expect 'please'");
        assertThat(console.run(g.id, a, "thresh 0,0 food 100 now").error()).contains("did not expect 'now'");
        assertThat(console.run(g.id, a, "demob 0,0 keep 10 extra").error()).contains("did not expect 'extra'");
        assertThat(console.run(g.id, a, "demob 0,0 all extra").error()).contains("did not expect 'extra'");
        assertThat(games.get(g.id).world).isSameAs(before);
    }
}
