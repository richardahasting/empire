package org.hastingtx.empire.server.persistence;

import org.hastingtx.empire.engine.update.Event;
import org.hastingtx.empire.engine.update.Flow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** Append-only. */
@Repository
public class LogRepository {
    private final JdbcTemplate jdbc;
    private final Json json;
    public LogRepository(JdbcTemplate jdbc, Json json) { this.jdbc = jdbc; this.json = json; }

    public void update(long gameId, long updateNumber, long seed, String hash, List<Event> events, List<Flow> flows, long millis, Map<String, List<String>> notes, Map<Long, List<String>> shipNotes) {
        jdbc.update("INSERT INTO update_log (game_id, update_number, seed, state_hash, events, flows, millis, notes, ship_notes) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb,?::jsonb)",
                gameId, updateNumber, seed, hash, json.write(events), json.write(flows), millis, json.write(notes), json.write(shipNotes));
    }

    /** One update of a ship's logbook. */
    public record ShipLogEntry(long updateNumber, List<String> lines) {}

    /**
     * A ship's logbook, newest first, for the last {@code updates} updates she has an entry in (issue #67).
     * A ship writes a line every update she exists, so this reads a handful of the newest rows.
     */
    @SuppressWarnings("unchecked")
    public List<ShipLogEntry> shipHistory(long gameId, long shipId, int updates) {
        String key = Long.toString(shipId);
        return jdbc.query("SELECT update_number, (ship_notes -> ?)::text FROM update_log WHERE game_id = ? AND ship_notes -> ? IS NOT NULL ORDER BY update_number DESC LIMIT ?",
                (rs, i) -> new ShipLogEntry(rs.getLong(1), json.read(rs.getString(2), List.class)), key, gameId, key, updates);
    }

    public void command(long gameId, int countryId, long updateNumber, String source, String verb, Object payload, boolean accepted, String error, double btu) {
        jdbc.update("INSERT INTO command_log (game_id, country_id, update_number, source, verb, payload, accepted, error, btu_spent) VALUES (?,?,?,?,?,?::jsonb,?,?,?)",
                gameId, countryId, updateNumber, source, verb, json.write(payload), accepted, error, btu);
    }

    /** A deity's edit (issue #128). Recorded because it breaks conservation and determinism. */
    public void deityEdit(long gameId, long updateNumber, String target, String changes, Long accountId) {
        jdbc.update("INSERT INTO deity_edit (game_id, update_number, target, changes, account_id) VALUES (?,?,?,?,?)",
                gameId, updateNumber, target, changes, accountId);
    }

    /** Every edit made to a game, newest first. */
    public List<Map<String, Object>> deityEdits(long gameId) {
        return jdbc.queryForList("SELECT id, update_number, target, changes, account_id, at FROM deity_edit WHERE game_id = ? ORDER BY id DESC", gameId);
    }

    public record UpdateEntry(long updateNumber, long seed, String stateHash, String eventsJson, String flowsJson, long millis, String notesJson) {}

    public UpdateEntry lastUpdate(long gameId) {
        List<UpdateEntry> l = jdbc.query("SELECT update_number, seed, state_hash, events::text AS e, flows::text AS f, millis, notes::text AS n FROM update_log WHERE game_id = ? ORDER BY update_number DESC LIMIT 1",
                (rs, i) -> new UpdateEntry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString("e"), rs.getString("f"), rs.getLong(6), rs.getString("n")), gameId);
        return l.isEmpty() ? null : l.get(0);
    }

    public record EventLine(long updateNumber, String message) {}

    /** One country's events of the given types over the last {@code updates} updates, oldest first, filtered in the database. */
    public List<EventLine> recentEvents(long gameId, int country, java.util.Collection<String> types, int updates) {
        if (types.isEmpty()) return List.of();
        String in = String.join(",", java.util.Collections.nCopies(types.size(), "?"));
        List<Object> args = new java.util.ArrayList<>(List.of(gameId, gameId, updates, String.valueOf(country)));
        args.addAll(types);
        return jdbc.query("SELECT u.update_number, e->>'message' FROM update_log u, jsonb_array_elements(u.events) e"
                        + " WHERE u.game_id = ? AND u.update_number > (SELECT coalesce(max(update_number), 0) FROM update_log WHERE game_id = ?) - ?"
                        + " AND e->>'country' = ? AND e->>'type' IN (" + in + ") ORDER BY u.update_number",
                (rs, i) -> new EventLine(rs.getLong(1), rs.getString(2)), args.toArray());
    }

    public List<Map<String, Object>> updates(long gameId) {
        return jdbc.queryForList("SELECT update_number, state_hash, ran_at, millis FROM update_log WHERE game_id = ? ORDER BY update_number", gameId);
    }
}
