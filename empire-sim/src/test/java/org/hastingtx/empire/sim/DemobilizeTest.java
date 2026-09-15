package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #217. Richard, 2026-09-14, after 12,000 soldiers in one warehouse turned a surplus into a $31k
 * loss an update: "you need to create the command to demobilize the military. Because that definitely
 * needs to be there."
 */
class DemobilizeTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord AT = Hex.stepRaw(TestWorlds.CENTER, 1, 1);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    private static World world(double civ, double mil) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        return TestWorlds.own(w, CFG, AT, "warehouse", 100, 127, Map.of("civ", civ, "mil", mil, "food", 5000.0), Map.of());
    }

    @Test
    void theyBecomeCiviliansWhileThereIsRoomAndTheRestGoHome() {
        CommandResult r = EX.execute(world(700, 12000), 0, new Command.Demobilize(AT, 1000, true));
        assertThat(r.error()).as(r.error()).isNull();
        Sector s = r.world().sector(AT);
        assertThat(s.stock().get(COM.mil)).isEqualTo(1000);
        assertThat(s.stock().get(COM.civ)).as("up to the cap of 1000").isEqualTo(1000);
        assertThat(r.info()).contains("11000.0 military stood down").contains("300.0 became civilians").contains("10700.0 went home").contains("1000.0 remain");
        assertThat(r.btuSpent()).isEqualTo(1);
    }

    @Test
    void aNumberStandsThatManyDownAndAllStandsEveryoneDown() {
        World w = world(100, 500);
        Sector some = EX.execute(w, 0, new Command.Demobilize(AT, 200, false)).world().sector(AT);
        assertThat(some.stock().get(COM.mil)).isEqualTo(300);
        assertThat(some.stock().get(COM.civ)).isEqualTo(300);
        assertThat(EX.execute(w, 0, new Command.Demobilize(AT, 9999, false)).world().sector(AT).stock().get(COM.mil)).as("no more than are there").isZero();
        assertThat(EX.execute(w, 0, new Command.Demobilize(AT, 0, true)).world().sector(AT).stock().get(COM.mil)).as("all").isZero();
    }

    @Test
    void theRefusals() {
        assertThat(EX.execute(world(100, 0), 0, new Command.Demobilize(AT, 0, true)).error()).contains("no military");
        assertThat(EX.execute(world(100, 50), 0, new Command.Demobilize(AT, 80, true)).error()).contains("no more than the 80.0 to keep");
        assertThat(EX.execute(world(100, 50), 0, new Command.Demobilize(AT, -1, false)).error()).contains("0 or more");
        assertThat(EX.execute(world(100, 50), 0, new Command.Demobilize(new Coord(20, 20), 10, false)).error()).contains("do not own");
    }

    @Test
    void thePayStopsAndTheBooksBalance() {
        World before = world(700, 12000);
        World after = EX.execute(before, 0, new Command.Demobilize(AT, 1000, true)).world();
        double kept = Update.run(after, CFG, 3).next().country(0).cash() - after.country(0).cash();
        double paid = Update.run(before, CFG, 3).next().country(0).cash() - before.country(0).cash();
        assertThat(kept - paid).as("eleven thousand fewer soldiers on the payroll").isGreaterThan(11000 * CFG.economy().money().payPerMilPerEtu() * CFG.etus() * 0.9);
    }
}
