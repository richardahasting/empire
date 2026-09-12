package org.hastingtx.empire.server.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** The world-visible feed (issue #121). Stores who by id, never by name — names change. */
@Repository
public class NewsRepository {
    private final JdbcTemplate jdbc;
    public NewsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Item(long id, long updateNumber, Instant at, String type, Integer countryId, String message) {}

    /** Post an item. Returns false for a one-shot somebody already claimed. */
    public boolean post(long gameId, long updateNumber, String type, Integer countryId, String message, String oneShotKey) {
        int n = jdbc.update("""
                INSERT INTO news (game_id, update_number, type, country_id, message, one_shot_key)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (game_id, one_shot_key) WHERE one_shot_key IS NOT NULL DO NOTHING""",
                gameId, updateNumber, type, countryId, message, oneShotKey);
        return n == 1;
    }

    public List<Item> since(long gameId, long afterId, int limit) {
        return jdbc.query("SELECT id, update_number, at, type, country_id, message FROM news WHERE game_id = ? AND id > ? ORDER BY id DESC LIMIT ?",
                (r, i) -> new Item(r.getLong("id"), r.getLong("update_number"), r.getTimestamp("at").toInstant(),
                        r.getString("type"), (Integer) r.getObject("country_id"), r.getString("message")),
                gameId, afterId, limit);
    }

    public long lastSeen(long accountId, long gameId) {
        List<Long> v = jdbc.queryForList("SELECT last_seen FROM news_seen WHERE account_id = ? AND game_id = ?", Long.class, accountId, gameId);
        return v.isEmpty() || v.get(0) == null ? 0 : v.get(0);
    }

    public void markSeen(long accountId, long gameId, long upTo) {
        jdbc.update("""
                INSERT INTO news_seen (account_id, game_id, last_seen) VALUES (?,?,?)
                ON CONFLICT (account_id, game_id) DO UPDATE SET last_seen = GREATEST(news_seen.last_seen, EXCLUDED.last_seen)""",
                accountId, gameId, upTo);
    }

    public int unseen(long accountId, long gameId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM news WHERE game_id = ?
                  AND id > COALESCE((SELECT last_seen FROM news_seen WHERE account_id = ? AND game_id = ?), 0)""",
                Integer.class, gameId, accountId, gameId);
        return n == null ? 0 : n;
    }
}
