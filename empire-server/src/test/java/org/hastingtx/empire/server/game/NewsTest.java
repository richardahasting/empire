package org.hastingtx.empire.server.game;

import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.server.auth.Account;
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

/** Issue #121: the feed, and the two things about it that are easy to get wrong. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class NewsTest {
    @Autowired GameService games;
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

    private GameService.Game newGame(int seats) {
        GameService.Game g = games.createWithSeats("teaching", "news-test-" + System.nanoTime(), seats, 11L, null, WorldOverrides.NONE);
        made.add(g.id);
        return g;
    }

    @Test
    void joiningAndStartingAreNews() {
        GameService.Game g = newGame(2);
        games.join(g.id, account("a"), 0, "Ruritania");
        games.join(g.id, account("b"), 1, "Freedonia");

        var feed = games.newsFor(g.id, account("reader"), 50);
        assertThat(feed).extracting(GameService.NewsItem::text)
                .anySatisfy(t -> assertThat(t).contains("Ruritania").contains("joined"))
                .anySatisfy(t -> assertThat(t).contains("The game has begun"));
    }

    /**
     * The reason news stores ids: a rename makes every earlier item about that country wrong, and the
     * item announcing the rename would be the first to go stale.
     */
    @Test
    void olderNewsFollowsARename() {
        GameService.Game g = newGame(2);
        Account a = account("a");
        games.join(g.id, a, 0, "Ruritania");
        assertThat(games.newsFor(g.id, a, 50)).anySatisfy(i -> assertThat(i.text()).contains("Ruritania"));

        games.renameMine(g.id, a, "Sylvania");

        var feed = games.newsFor(g.id, a, 50);
        assertThat(feed).extracting(GameService.NewsItem::text)
                .as("the join notice now names the country as it is now")
                .anySatisfy(t -> assertThat(t).isEqualTo("Sylvania joined the game"));
        assertThat(feed).noneSatisfy(i -> assertThat(i.text()).contains("Ruritania joined"));
        assertThat(feed).anySatisfy(i -> assertThat(i.text()).isEqualTo("Ruritania is now known as Sylvania"));
    }

    @Test
    void aFirstIsAnnouncedOnceAndOnlyOnce() {
        GameService.Game g = newGame(2);
        Account deity = account("deity");
        games.join(g.id, account("a"), 0, "Ruritania");
        games.join(g.id, account("b"), 1, "Freedonia");

        // build a refinery by hand, twice, in two different countries
        Coord one = g.world.country(0).capital();
        Coord two = g.world.country(1).capital();
        games.editSector(g.id, one.x(), one.y(), designate("refinery"), deity);
        games.forceUpdate(g.id);
        games.editSector(g.id, two.x(), two.y(), designate("refinery"), deity);
        games.forceUpdate(g.id);

        var firsts = games.newsFor(g.id, deity, 100).stream().filter(i -> i.type().equals("milestone")).toList();
        assertThat(firsts).as("one refinery milestone, not two").hasSize(1);
        assertThat(firsts.get(0).text()).contains("Ruritania").contains("first refinery");
    }

    private static GameService.SectorEdit designate(String d) {
        return new GameService.SectorEdit(null, d, 100.0, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void unseenCountsDownAsYouRead() {
        GameService.Game g = newGame(2);
        Account reader = account("reader");
        games.join(g.id, account("a"), 0, "Ruritania");

        assertThat(games.unseenNews(g.id, reader)).isGreaterThan(0);
        games.markNewsSeen(g.id, reader);
        assertThat(games.unseenNews(g.id, reader)).isZero();

        games.join(g.id, account("b"), 1, "Freedonia");
        assertThat(games.unseenNews(g.id, reader)).as("new items are unseen again").isGreaterThan(0);
    }

    @Test
    void theFeedIsNewestFirstAndMarksWhatIsNew() {
        GameService.Game g = newGame(2);
        Account reader = account("reader");
        games.join(g.id, account("a"), 0, "Ruritania");
        games.join(g.id, account("b"), 1, "Freedonia");   // two joins plus the bell: enough to order
        var feed = games.newsFor(g.id, reader, 50);
        assertThat(feed).hasSizeGreaterThan(1);
        assertThat(feed.get(0).id()).isGreaterThan(feed.get(feed.size() - 1).id());
        assertThat(feed).allSatisfy(i -> assertThat(i.unseen()).isTrue());
    }
}
