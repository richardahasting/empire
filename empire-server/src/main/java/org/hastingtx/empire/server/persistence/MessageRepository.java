package org.hastingtx.empire.server.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * The post (issue #140). Deliberately outside world state: the engine charges for a message and the
 * server keeps it, so an update never depends on what anybody said.
 */
@Repository
public class MessageRepository {
    private final JdbcTemplate jdbc;
    public MessageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** {@code toCountry} null means an announcement. */
    public record Message(long id, long updateNumber, Instant at, int fromCountry, Integer toCountry, String body) {}

    public long post(long gameId, long updateNumber, int from, Integer to, String body) {
        return jdbc.queryForObject(
                "INSERT INTO message (game_id, update_number, from_country, to_country, body) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, gameId, updateNumber, from, to, body);
    }

    /** Everything this country may read: its own post, its announcements, and what it has sent. */
    public List<Message> forCountry(long gameId, int countryId, int limit) {
        return jdbc.query("""
                SELECT id, update_number, at, from_country, to_country, body FROM message
                 WHERE game_id = ? AND (to_country IS NULL OR to_country = ? OR from_country = ?)
                 ORDER BY id DESC LIMIT ?""",
                (r, i) -> new Message(r.getLong("id"), r.getLong("update_number"), r.getTimestamp("at").toInstant(),
                        r.getInt("from_country"), (Integer) r.getObject("to_country"), r.getString("body")),
                gameId, countryId, countryId, limit);
    }

    /** Unread: addressed to this country or to everyone, from somebody else, since the watermark. */
    public int unread(long gameId, int countryId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM message
                 WHERE game_id = ? AND from_country <> ? AND (to_country IS NULL OR to_country = ?)
                   AND id > COALESCE((SELECT last_seen FROM message_seen WHERE game_id = ? AND country_id = ?), 0)""",
                Integer.class, gameId, countryId, countryId, gameId, countryId);
        return n == null ? 0 : n;
    }

    public void markSeen(long gameId, int countryId, long upTo) {
        jdbc.update("""
                INSERT INTO message_seen (game_id, country_id, last_seen) VALUES (?,?,?)
                ON CONFLICT (game_id, country_id) DO UPDATE SET last_seen = GREATEST(message_seen.last_seen, EXCLUDED.last_seen)""",
                gameId, countryId, upTo);
    }

    /** How far this country has read. */
    public long lastSeen(long gameId, int countryId) {
        List<Long> v = jdbc.queryForList("SELECT last_seen FROM message_seen WHERE game_id = ? AND country_id = ?", Long.class, gameId, countryId);
        return v.isEmpty() || v.get(0) == null ? 0 : v.get(0);
    }

    public long newestId(long gameId) {
        List<Long> v = jdbc.queryForList("SELECT id FROM message WHERE game_id = ? ORDER BY id DESC LIMIT 1", Long.class, gameId);
        return v.isEmpty() ? 0 : v.get(0);
    }
}
