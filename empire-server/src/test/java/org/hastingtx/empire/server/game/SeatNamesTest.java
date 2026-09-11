package org.hastingtx.empire.server.game;

import org.hastingtx.empire.config.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Issue #117: the roster is a count, and the seats are named emp1 … empN. */
class SeatNamesTest {

    @Test
    void seatsAreNumberedFromOne() {
        assertEquals(List.of("emp1"), WorldOverrides.seatNames(1));
        assertEquals(List.of("emp1", "emp2", "emp3"), WorldOverrides.seatNames(3));
        assertEquals(64, WorldOverrides.seatNames(64).size());
        assertEquals("emp64", WorldOverrides.seatNames(64).get(63));
    }

    @Test
    void theRosterHasLimits() {
        assertThrows(IllegalArgumentException.class, () -> WorldOverrides.seatNames(0));
        assertThrows(IllegalArgumentException.class, () -> WorldOverrides.seatNames(65));
    }

    @Test
    void theCountPatchesMaxCountries() {
        var raw = new ConfigLoader().loadSchema().raw();
        var out = new WorldOverrides(null, null, null, null, null, null, null, null, null, 12).patch(raw, 12);
        @SuppressWarnings("unchecked")
        Map<String, Object> players = (Map<String, Object>) out.get("players");
        assertEquals(12, players.get("max_countries"));
    }

    @Test
    void aCountBeyondTheCapIsRefused() {
        var raw = new ConfigLoader().loadSchema().raw();
        var over = new WorldOverrides(null, null, null, null, null, null, null, null, null, 65);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> over.patch(raw, 65));
        assertTrue(e.getMessage().contains("64"), e.getMessage());
    }

    /** A roster the map cannot physically seat is refused at creation, not discovered slowly (#108). */
    @Test
    void aCountTheMapCannotSeatIsRefused() {
        var raw = new ConfigLoader().loadSchema().raw();
        var tight = new WorldOverrides(16, 16, null, null, null, 12, null, null, null, 40);
        assertThrows(IllegalArgumentException.class, () -> tight.patch(raw, 40));
    }

    @Test
    void thePatchedConfigStillBinds() {
        ConfigLoader loader = new ConfigLoader();
        var raw = new WorldOverrides(64, 64, null, null, null, null, null, null, null, 16).patch(loader.loadSchema().raw(), 16);
        var cfg = loader.loadYaml(loader.toYaml(raw)).config();
        assertEquals(16, cfg.players().maxCountries());
    }
}
