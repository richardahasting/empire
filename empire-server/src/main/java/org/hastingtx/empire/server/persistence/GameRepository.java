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

    public void setStatus(long id, String status) { db.sql("UPDATE game SET status = :s WHERE id = :id").param("s", status).param("id", id).update(); }
    public void setUpdateNumber(long id, long n) { db.sql("UPDATE game SET update_number = :n WHERE id = :id").param("n", n).param("id", id).update(); }

    private GameRow map(java.sql.ResultSet r, int i) throws java.sql.SQLException {
        Timestamp ts = r.getTimestamp("created_at");
        long by = r.getLong("created_by");
        Long createdBy = r.wasNull() ? null : by;   // wasNull() refers to the LAST column read
        return new GameRow(r.getLong("id"), r.getString("name"), r.getString("preset"), r.getString("config_yaml"), r.getString("config_hash"),
                r.getLong("seed"), r.getString("status"), r.getLong("update_number"), r.getInt("width"), r.getInt("height"),
                r.getBoolean("wrap_x"), r.getBoolean("wrap_y"), ts == null ? null : ts.toInstant(), createdBy);
    }

    /** Who controls which country. Server-side only. */
    public record Seat(int countryId, String name, Long accountId, String controller) {}

    public List<Seat> seats(long gameId) {
        return db.sql("SELECT country_id, name, account_id, controller FROM country WHERE game_id = :g ORDER BY country_id").param("g", gameId)
                .query((r, i) -> { long a = r.getLong("account_id"); Long acct = r.wasNull() ? null : a; return new Seat(r.getInt("country_id"), r.getString("name"), acct, r.getString("controller")); }).list();
    }

    public int bind(long gameId, int countryId, long accountId) {
        return db.sql("UPDATE country SET account_id = :a, controller = 'human' WHERE game_id = :g AND country_id = :c AND account_id IS NULL")
                .param("a", accountId).param("g", gameId).param("c", countryId).update();
    }

    public Optional<Integer> countryOf(long gameId, long accountId) {
        return db.sql("SELECT country_id FROM country WHERE game_id = :g AND account_id = :a").param("g", gameId).param("a", accountId).query(Integer.class).optional();
    }
}
