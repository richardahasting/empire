package org.hastingtx.empire.server.console;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue #71: {@code bomb PLANE x,y [strategic] [escort E,E ...]} — the escort list, by commas or spaces. */
class EscortParseTest {
    private static final String U = "bomb PLANE x,y [strategic] [escort E,E...]";

    @Test
    void escortsByCommasOrSpaces() {
        assertThat(Console.escorts("bomb 1 2,3".split(" "), 3, U)).isEmpty();
        assertThat(Console.escorts("bomb 1 2,3 escort 5,6".split(" "), 3, U)).containsExactly(5L, 6L);
        assertThat(Console.escorts("bomb 1 2,3 strategic escort #5 6".split(" "), 4, U)).containsExactly(5L, 6L);
        assertThat(Console.escorts("bomb 1 2,3 ESCORT 7,".split(" "), 3, U)).isEqualTo(List.of(7L));
    }

    @Test
    void anythingElseIsRefused() {
        assertThatThrownBy(() -> Console.escorts("bomb 1 2,3 please".split(" "), 3, U)).hasMessageContaining("usage");
        assertThatThrownBy(() -> Console.escorts("bomb 1 2,3 escort".split(" "), 3, U)).hasMessageContaining("usage");
        assertThatThrownBy(() -> Console.escorts("bomb 1 2,3 escort five".split(" "), 3, U)).hasMessageContaining("'five' is not a plane number");
    }
}
