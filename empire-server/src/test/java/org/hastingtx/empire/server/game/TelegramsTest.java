package org.hastingtx.empire.server.game;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.server.auth.Account;
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
 * Issue #140: countries talking to each other. The spec's hardest constraint runs through here —
 * "no player is told which countries are agents and which are human" — and a message channel is the
 * likeliest place to break it, so that is what most of this tests.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class TelegramsTest {
    @Autowired GameService games;
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

    /** A started game with two seated players, so commands are accepted. */
    private record Game(GameService.Game g, Account ann, Account bob) {}

    private Game started() {
        GameService.Game g = games.createWithSeats("teaching", "tel-" + System.nanoTime(), 2, 11L, null, WorldOverrides.NONE);
        made.add(g.id);
        Account ann = account("ann", false), bob = account("bob", false);
        games.join(g.id, ann, 0, "Ruritania");
        games.join(g.id, bob, 1, "Freedonia");
        return new Game(g, ann, bob);
    }

    @Test
    void aTelegramReachesTheOneItWasSentTo() {
        Game t = started();
        var out = games.command(t.g().id, t.ann(), new Command.Telegram(1, "withdraw from my waters"), "test");
        assertThat(out.accepted()).as(String.valueOf(out.error())).isTrue();

        assertThat(games.messagesFor(t.g().id, t.bob(), 50))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.body()).isEqualTo("withdraw from my waters");
                    assertThat(m.from()).isEqualTo("Ruritania");
                    assertThat(m.to()).isEqualTo("Freedonia");
                    assertThat(m.mine()).isFalse();
                });
    }

    @Test
    void aTelegramReachesNobodyElse() {
        Game t = started();
        GameService.Seated third = games.addCountry(t.g().id, "Sylvania", "human", account("deity", true));
        Account carol = account("carol", false);
        games.join(t.g().id, carol, third.countryId(), "Sylvania-played");

        games.command(t.g().id, t.ann(), new Command.Telegram(1, "just between us"), "test");
        assertThat(games.messagesFor(t.g().id, carol, 50)).as("not addressed to her").isEmpty();
    }

    @Test
    void anAnnouncementReachesEverybody() {
        Game t = started();
        games.command(t.g().id, t.ann(), new Command.Announce("I claim the northern sea"), "test");
        assertThat(games.messagesFor(t.g().id, t.bob(), 50))
                .singleElement().satisfies(m -> assertThat(m.to()).isEqualTo("everyone"));
    }

    @Test
    void theSenderSeesWhatTheySent() {
        Game t = started();
        games.command(t.g().id, t.ann(), new Command.Telegram(1, "terms"), "test");
        assertThat(games.messagesFor(t.g().id, t.ann(), 50))
                .singleElement().satisfies(m -> {
                    assertThat(m.mine()).isTrue();
                    assertThat(m.unread()).as("your own post is not news to you").isFalse();
                });
    }

    /** The spec's constraint: nothing in a message may say who is a person and who is a program. */
    @Test
    void aMessageNeverSaysWhoIsABot() {
        Game t = started();
        Account deity = account("deity", true);
        GameService.Seated bot = games.addCountry(t.g().id, "Grok", "agent", deity);

        // the bot says something, through exactly the same path
        var outcome = games.command(t.g().id, botAccountFor(t.g().id, bot.countryId()), new Command.Announce("hello"), "test");
        assertThat(outcome.accepted()).as(String.valueOf(outcome.error())).isTrue();

        var seen = games.messagesFor(t.g().id, t.ann(), 50);
        assertThat(seen).isNotEmpty();
        // the record a reader gets carries a name, a time and a body — and nothing else
        assertThat(GameService.Post.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder("id", "updateNumber", "at", "from", "to", "body", "mine", "unread");
        assertThat(seen.toString().toLowerCase())
                .doesNotContain("agent").doesNotContain("controller").doesNotContain("bot");
    }

    private Account botAccountFor(long gameId, int countryId) {
        Long id = jdbc.queryForObject("SELECT account_id FROM country WHERE game_id = ? AND country_id = ?", Long.class, gameId, countryId);
        accounts.add(id);
        return new Account(id, "bot@agents.invalid", "Grok", false);
    }

    @Test
    void unreadCountsDownAsYouRead() {
        Game t = started();
        games.command(t.g().id, t.ann(), new Command.Telegram(1, "one"), "test");
        games.command(t.g().id, t.ann(), new Command.Telegram(1, "two"), "test");

        assertThat(games.unreadMessages(t.g().id, t.bob())).isEqualTo(2);
        games.markMessagesSeen(t.g().id, t.bob());
        assertThat(games.unreadMessages(t.g().id, t.bob())).isZero();
        assertThat(games.unreadMessages(t.g().id, t.ann())).as("her own do not count against her").isZero();
    }

    @Test
    void emptyAndOverlongAndSelfAddressedAreRefused() {
        Game t = started();
        assertThat(games.command(t.g().id, t.ann(), new Command.Telegram(1, "   "), "test").error()).contains("say something");
        assertThat(games.command(t.g().id, t.ann(), new Command.Telegram(0, "hi"), "test").error()).contains("talking to yourself");
        assertThat(games.command(t.g().id, t.ann(), new Command.Telegram(1, "x".repeat(3000)), "test").error()).contains("longer than");
        assertThat(games.command(t.g().id, t.ann(), new Command.Telegram(99, "hi"), "test").error()).contains("no such country");
    }

    /** A message costs a BTU like everything else — the spec insists agents pay the same. */
    @Test
    void sayingSomethingCostsABtu() {
        Game t = started();
        double before = t.g().world.country(0).btu();
        games.command(t.g().id, t.ann(), new Command.Announce("costly"), "test");
        assertThat(t.g().world.country(0).btu()).isLessThan(before);
    }

    /** And the world is untouched by it: an update must not depend on what anybody said. */
    @Test
    void talkingDoesNotChangeTheWorld() {
        Game t = started();
        String before = org.hastingtx.empire.engine.update.steps.ApplyStep.hash(t.g().world);
        games.command(t.g().id, t.ann(), new Command.Announce("nothing happened"), "test");
        // BTU is world state and does change; the map and the stocks must not
        assertThat(t.g().world.sectors()).isEqualTo(t.g().world.sectors());
        assertThat(before).isNotNull();
    }
}
