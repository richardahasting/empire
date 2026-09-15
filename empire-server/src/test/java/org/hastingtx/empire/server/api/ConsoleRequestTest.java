package org.hastingtx.empire.server.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #225 (Wolfy, 2026-09-15): {@code POST /console} with {@code {"command": "..."}} answered accepted:true with empty
 * output and did nothing; only {@code line} worked. Silent success is worse than a 400 (IllegalArgumentException is a
 * 400 in {@link ApiErrors}).
 */
class ConsoleRequestTest {
    @Test
    void lineAndCommandBothWork() {
        assertThat(GameController.ConsoleRequest.of(Map.of("line", "move lcm 10,-1 8,0 50")).line()).isEqualTo("move lcm 10,-1 8,0 50");
        assertThat(GameController.ConsoleRequest.of(Map.of("command", "move lcm 10,-1 8,0 50")).line()).isEqualTo("move lcm 10,-1 8,0 50");
        assertThat(GameController.ConsoleRequest.of(Map.of("line", "")).line()).as("an empty line is still a line").isEmpty();
    }

    @Test
    void anythingElseIsRefusedNotIgnored() {
        assertThatThrownBy(() -> GameController.ConsoleRequest.of(Map.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no console line");
        assertThatThrownBy(() -> GameController.ConsoleRequest.of(Map.of("cmd", "census"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown key \"cmd\"");
        assertThatThrownBy(() -> GameController.ConsoleRequest.of(Map.of("line", "map", "command", "census"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not both");
        Map<String, Object> nullLine = new HashMap<>();
        nullLine.put("line", null);
        assertThatThrownBy(() -> GameController.ConsoleRequest.of(nullLine)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GameController.ConsoleRequest.of(Map.of("line", 42))).isInstanceOf(IllegalArgumentException.class);
    }
}
