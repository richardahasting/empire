package org.hastingtx.empire.server.macro;

import org.hastingtx.empire.server.persistence.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Macros live per account, not per game: the same boilerplate applies everywhere. Issue #47. */
@Repository
public class MacroRepository {
    public record MacroRow(int slot, String name, List<Map<String, Object>> steps) {}

    private final JdbcClient db;
    private final Json json;
    public MacroRepository(JdbcClient db, Json json) { this.db = db; this.json = json; }

    @SuppressWarnings("unchecked")
    private MacroRow map(java.sql.ResultSet r, int i) throws java.sql.SQLException {
        return new MacroRow(r.getInt("slot"), r.getString("name"), json.read(r.getString("steps"), List.class));
    }

    public List<MacroRow> list(long accountId) {
        return db.sql("SELECT slot, name, steps::text AS steps FROM macro WHERE account_id = :a ORDER BY slot").param("a", accountId).query(this::map).list();
    }
    public Optional<MacroRow> find(long accountId, int slot) {
        return db.sql("SELECT slot, name, steps::text AS steps FROM macro WHERE account_id = :a AND slot = :s").param("a", accountId).param("s", slot).query(this::map).optional();
    }
    public void put(long accountId, int slot, String name, List<Map<String, Object>> steps) {
        db.sql("""
                INSERT INTO macro (account_id, slot, name, steps, updated_at) VALUES (:a, :s, :n, :j::jsonb, now())
                ON CONFLICT (account_id, slot) DO UPDATE SET name = EXCLUDED.name, steps = EXCLUDED.steps, updated_at = now()""")
                .param("a", accountId).param("s", slot).param("n", name).param("j", json.write(steps)).update();
    }
    public void delete(long accountId, int slot) {
        db.sql("DELETE FROM macro WHERE account_id = :a AND slot = :s").param("a", accountId).param("s", slot).update();
    }
}
