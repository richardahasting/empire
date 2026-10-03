package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.DoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #278. The console parses numbers with Double.parseDouble, which takes "NaN" and "Infinity"; NaN fails every
 * comparison, so guards written {@code qty <= 0} let it into the stock. Every verb that carries a number refuses it,
 * and the world comes back untouched.
 */
class NonFiniteCommandTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Coord AT = Hex.stepRaw(TestWorlds.CENTER, 1, 1);
    private static final Coord NEXT = Hex.stepRaw(TestWorlds.CENTER, 0, 1);
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final double[] BAD = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};

    private static World world() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        return TestWorlds.own(w, CFG, AT, "warehouse", 100, 127, Map.of("civ", 400.0, "mil", 100.0, "food", 500.0), Map.of());
    }

    private static void refused(String field, DoubleFunction<Command> make) {
        World w = world();
        for (double bad : BAD) {
            CommandResult r = EX.execute(w, 0, make.apply(bad));
            assertThat(r.error()).as(field + " = " + bad).isEqualTo(field + " must be a number");
            assertThat(r.world()).as("nothing applied").isSameAs(w);
            assertThat(r.btuSpent()).isZero();
        }
    }

    @Test
    void everySectorOrderRefusesIt() {
        refused("qty", q -> new Command.Demobilize(AT, q, false));
        refused("amount", a -> new Command.Threshold(AT, "food", a));
        refused("threshold", t -> new Command.Deliver(AT, "food", 0, t));
        refused("targetLevel", l -> new Command.BuildRoad(AT, l));
        refused("targetLevel", l -> new Command.BuildRail(AT, l));
    }

    @Test
    void everyMovementRefusesIt() {
        refused("qty", q -> new Command.Move(AT, NEXT, "food", q));
        refused("qty", q -> new Command.RailShip(AT, NEXT, "food", q));
        refused("civs", c -> new Command.Explore(AT, NEXT, c));
    }

    @Test
    void shipsAndUnitsRefuseIt() {
        refused("qty", q -> new Command.Load(1, "food", q));
        refused("qty", q -> new Command.Unload(1, "food", q));
        refused("qty", q -> new Command.LoadUnit(1, "food", q, false));
        refused("mobility", m -> new Command.Work(1, m));
    }

    @Test
    void aNumberNestedInAListIsFoundToo() {
        refused("parties.mil", m -> new Command.Attack(NEXT, List.of(new Command.Attack.Party(AT, 10), new Command.Attack.Party(AT, m))));
    }

    @Test
    void ordinaryNumbersStillPass() {
        CommandResult r = EX.execute(world(), 0, new Command.Demobilize(AT, 10, false));
        assertThat(r.error()).isNull();
        assertThat(r.world().sector(AT).stock().get(Commodities.of(CFG).mil)).isEqualTo(90);
    }
}
