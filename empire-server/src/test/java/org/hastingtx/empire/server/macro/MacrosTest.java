package org.hastingtx.empire.server.macro;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.sim.Sim;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue #47: a macro is panel commands with the sector left blank; expansion yields ordinary commands. */
class MacrosTest {
    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();
    private static final World W = new Sim(CFG).newWorld(List.of("P", "Q"), 3);
    private static final CountryView V = CountryView.of(W, CFG, 0);

    static final List<Map<String, Object>> SETTLE = List.of(
            Map.of("verb", "threshold", "commodity", "civ", "amount", 800),
            Map.of("verb", "threshold", "commodity", "food", "amount", 500),
            Map.of("verb", "distribute", "center", "capital"),
            Map.of("verb", "build_road", "amount", 100),
            Map.of("verb", "deliver", "commodity", "lcm", "direction", "w", "amount", 0));

    @Test
    void expandsToOrdinaryCommandsInOrder() {
        List<Command> cmds = Macros.expand(SETTLE, V, CFG, V.capital(), false);
        assertThat(cmds).hasSize(5);
        assertThat(((Command.Threshold) cmds.get(0)).amount()).isEqualTo(800);
        assertThat(((Command.Distribute) cmds.get(2)).center()).isEqualTo(V.capital());
        assertThat(((Command.BuildRoad) cmds.get(3)).targetLevel()).isEqualTo(100);
        assertThat(((Command.Deliver) cmds.get(4)).dir()).isEqualTo(3);
        assertThat(cmds).allMatch(c -> V.capital().equals(sectorOf(c)));
    }

    @Test
    void describesEachStepAsASentence() {
        assertThat(Macros.describe(SETTLE)).isEqualTo("threshold civ 800; threshold food 500; distribution centre: capital; road toward 100; deliver lcm w above 0");
        assertThat(Macros.describe(Map.of("verb", "threshold", "commodity", "food", "clear", true))).isEqualTo("clear threshold food");
        assertThat(Macros.describe(Map.of("verb", "distribute", "center", Map.of("dx", 2, "dy", -1)))).isEqualTo("distribution centre: 2,-1 from the capital");
    }

    @Test
    void refusesWhatAMacroCannotDo() {
        assertThatThrownBy(() -> Macros.validate("x", List.of(Map.of("verb", "move")))).hasMessageContaining("cannot move");
        assertThatThrownBy(() -> Macros.validate("", SETTLE)).hasMessageContaining("name");
        assertThatThrownBy(() -> Macros.validate("ok", List.of())).hasMessageContaining("at least one step");
    }

    private static Coord sectorOf(Command c) {
        return switch (c) {
            case Command.Threshold t -> t.sector();
            case Command.Distribute d -> d.sector();
            case Command.Deliver d -> d.sector();
            case Command.BuildRoad r -> r.sector();
            case Command.BuildRail r -> r.sector();
            case Command.Designate d -> d.sector();
            default -> null;
        };
    }
}
