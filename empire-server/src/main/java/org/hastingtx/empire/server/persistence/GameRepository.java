package org.hastingtx.empire.server.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class GameRepository {
    private final JdbcClient db;
    public GameRepository(JdbcClient db) { this.db = db; }

    public long create(String name, String preset, String configYaml, String configHash, long seed, int w, int h, boolean wx, boolean wy, Long createdBy) {
        return db.sql("""
                INSERT INTO game (name, preset, config_yaml, config_hash, seed, status, width, height, wrap_x, wrap_y, created_by)
                VALUES (:name, :preset, :yaml, :hash, :seed, 'setup', :w, :h, :wx, :wy, :by) RETURNING id""")
                .param("name", name).param("preset", preset).param("yaml", configYaml).param("hash", configHash).param("seed", seed)
                .param("w", w).param("h", h).param("wx", wx).param("wy", wy).param("by", createdBy)
                .query(Long.class).single();
    }

    public List<GameRow> all() { return db.sql("SELECT * FROM game ORDER BY id").query(this::map).list(); }
    public Optional<GameRow> find(long id) { return db.sql("SELECT * FROM game WHERE id = :id").param("id", id).query(this::map).optional(); }

    public void setConfig(long id, String configYaml, String configHash) { db.sql("UPDATE game SET config_yaml = :y, config_hash = :h WHERE id = :id").param("y", configYaml).param("h", configHash).param("id", id).update(); }
    /** Cascades to every game-owned table (issue #109). Returns rows deleted: 0 if it was already gone. */
    public int delete(long id) { return db.sql("DELETE FROM game WHERE id = :id").param("id", id).update(); }

    public void setStatus(long id, String status) { db.sql("UPDATE game SET status = :s WHERE id = :id").param("s", status).param("id", id).update(); }
    public void setSchedule(long id, long intervalSeconds, java.time.Instant nextUpdateAt) {
        db.sql("UPDATE game SET interval_seconds = :i, next_update_at = :n WHERE id = :id")
                .param("i", intervalSeconds).param("n", nextUpdateAt == null ? null : nextUpdateAt.atOffset(java.time.ZoneOffset.UTC)).param("id", id).update();
    }
    public void setUpdateNumber(long id, long n) { db.sql("UPDATE game SET update_number = :n WHERE id = :id").param("n", n).param("id", id).update(); }

    private GameRow map(java.sql.ResultSet r, int i) throws java.sql.SQLException {
        Timestamp ts = r.getTimestamp("created_at");
        long by = r.getLong("created_by");
        Long createdBy = r.wasNull() ? null : by;   // wasNull() refers to the LAST column read
        Timestamp next = r.getTimestamp("next_update_at");
        return new GameRow(r.getLong("id"), r.getString("name"), r.getString("preset"), r.getString("config_yaml"), r.getString("config_hash"),
                r.getLong("seed"), r.getString("status"), r.getLong("update_number"), r.getInt("width"), r.getInt("height"),
                r.getBoolean("wrap_x"), r.getBoolean("wrap_y"), ts == null ? null : ts.toInstant(), createdBy,
                r.getLong("interval_seconds"), next == null ? null : next.toInstant());
    }

    /**
     * Who controls which country. Server-side only. {@code reservedBy} is someone who has claimed the
     * seat but not yet proved their email (issue #126); the seat is theirs to confirm and nobody
     * else's to take, but it does not count as filled.
     */
    public record Seat(int countryId, String name, Long accountId, String controller,
                       Long reservedBy, java.time.Instant reservedUntil, String pendingName) {
        public boolean held() { return accountId != null; }
        public boolean reserved(java.time.Instant now) { return accountId == null && reservedBy != null && reservedUntil != null && reservedUntil.isAfter(now); }
        /** Nobody has it and nobody is in the middle of taking it. */
        public boolean open(java.time.Instant now) { return !held() && !reserved(now); }
    }

    public List<Seat> seats(long gameId) {
        return db.sql("SELECT country_id, name, account_id, controller, reserved_by, reserved_until, pending_name FROM country WHERE game_id = :g ORDER BY country_id")
                .param("g", gameId)
                .query((r, i) -> {
                    long a = r.getLong("account_id"); Long acct = r.wasNull() ? null : a;
                    long rb = r.getLong("reserved_by"); Long res = r.wasNull() ? null : rb;
                    var ru = r.getTimestamp("reserved_until");
                    return new Seat(r.getInt("country_id"), r.getString("name"), acct, r.getString("controller"),
                            res, ru == null ? null : ru.toInstant(), r.getString("pending_name"));
                }).list();
    }

    /** Hold a seat for someone who has not proved their email yet. Fails if anyone already has it. */
    public int reserve(long gameId, int countryId, long accountId, String pendingName, java.time.Instant until) {
        return db.sql("""
                UPDATE country SET reserved_by = :a, reserved_until = :u, pending_name = :n
                WHERE game_id = :g AND country_id = :c AND account_id IS NULL
                  AND (reserved_by IS NULL OR reserved_until < now() OR reserved_by = :a)""")
                .param("a", accountId).param("u", until.atOffset(java.time.ZoneOffset.UTC)).param("n", pendingName)
                .param("g", gameId).param("c", countryId).update();
    }

    /** Every live reservation this account holds, so clicking the link can settle them. */
    public List<long[]> reservationsFor(long accountId) {
        return db.sql("SELECT game_id, country_id FROM country WHERE reserved_by = :a AND account_id IS NULL AND reserved_until > now()")
                .param("a", accountId).query((r, i) -> new long[]{r.getLong("game_id"), r.getInt("country_id")}).list();
    }

    /** Turn a reservation into a seat. The pending name becomes the country's name. */
    public int confirm(long gameId, int countryId, long accountId) {
        return db.sql("""
                UPDATE country SET account_id = :a, controller = 'human',
                                   name = COALESCE(pending_name, name),
                                   reserved_by = NULL, reserved_until = NULL, pending_name = NULL
                WHERE game_id = :g AND country_id = :c AND account_id IS NULL AND reserved_by = :a""")
                .param("a", accountId).param("g", gameId).param("c", countryId).update();
    }

    /** Drop reservations nobody came back for. Returns how many seats were freed. */
    public int releaseStaleReservations() {
        return db.sql("UPDATE country SET reserved_by = NULL, reserved_until = NULL, pending_name = NULL WHERE account_id IS NULL AND reserved_until IS NOT NULL AND reserved_until < now()").update();
    }

    public int bind(long gameId, int countryId, long accountId) {
        return db.sql("UPDATE country SET account_id = :a, controller = 'human' WHERE game_id = :g AND country_id = :c AND account_id IS NULL")
                .param("a", accountId).param("g", gameId).param("c", countryId).update();
    }

    /** Mark the deity's own country (issue #128). It is never a seat and never counts as one. */
    public int seatDeity(long gameId, int countryId) {
        return db.sql("UPDATE country SET controller = 'deity' WHERE game_id = :g AND country_id = :c")
                .param("g", gameId).param("c", countryId).update();
    }

    /** Seat a bot: the country is played by this account from the moment it exists (issue #113). */
    public int seatAgent(long gameId, int countryId, long accountId) {
        return db.sql("UPDATE country SET account_id = :a, controller = 'agent' WHERE game_id = :g AND country_id = :c")
                .param("a", accountId).param("g", gameId).param("c", countryId).update();
    }

    public Optional<Integer> countryOf(long gameId, long accountId) {
        return db.sql("SELECT country_id FROM country WHERE game_id = :g AND account_id = :a").param("g", gameId).param("a", accountId).query(Integer.class).optional();
    }
}
