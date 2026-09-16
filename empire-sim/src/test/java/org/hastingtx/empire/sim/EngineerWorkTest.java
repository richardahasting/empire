package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #258 (#71 slice 2e): an engineer's own labour, as the original worked it (commands/work.c) — mobility ×
 * efficiency / 600 points of the sector's efficiency, bought with the materials a build always buys.
 */
class EngineerWorkTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord SITE = Hex.stepRaw(CAP, 1, 1);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    /** A half-built city of ours (the one type our rules build from materials) with an engineer standing in it. */
    private static World world(String type, double efficiency, double mobility, double unitEff, Map<String, Double> stock) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = w.withCountry(w.country(0).withCash(100000));
        w = TestWorlds.own(w, CFG, SITE, type, efficiency, 127, stock, Map.of());
        LandUnit u = new LandUnit(w.nextUnitId(), 0, "engineer", SITE, unitEff,
                Stocks.of(COM.fromMap(Map.of("mil", 20.0, "food", 12.0))), mobility, 200, 0, "", 0);
        return w.withUnit(u);
    }

    private static World world() {
        return world("city", 40, 127, 100, Map.of("civ", 400.0, "food", 500.0, "lcm", 500.0, "hcm", 500.0));
    }

    @Test
    void itBuildsTheSectorItStandsInAndPaysForIt() {
        World w = world();
        double cash = w.country(0).cash(), lcm = w.sector(SITE).stock().get(COM.index("lcm"));
        CommandResult r = EX.execute(w, 0, new Command.Work(1, 0));
        assertThat(r.error()).as(r.error()).isNull();
        Sector s = r.world().sector(SITE);
        assertThat(s.efficiency()).as("KNOWN: 127 mobility at 100% is 21 points").isEqualTo(40 + 21);
        assertThat(r.world().unit(1).mobility()).as("and it paid 600 mobility a point").isLessThan(10);
        assertThat(r.world().country(0).cash()).as("the treasury paid the build's cash").isLessThan(cash);
        assertThat(s.stock().get(COM.index("lcm"))).as("the sector's own materials went into it").isLessThan(lcm);
    }

    /** KNOWN work.c: the points are mobility × efficiency / 600, so a battered engineer does less with the same march. */
    @Test
    void aBatteredEngineerDoesLess() {
        double fit = built(EX.execute(world(), 0, new Command.Work(1, 60)));
        double worn = built(EX.execute(world("city", 40, 127, 50, Map.of("civ", 400.0, "food", 500.0, "lcm", 500.0, "hcm", 500.0)), 0, new Command.Work(1, 60)));
        assertThat(fit).isEqualTo(10);
        assertThat(worn).as("half the efficiency, half the work").isEqualTo(5);
    }

    private static double built(CommandResult r) {
        assertThat(r.error()).as(r.error()).isNull();
        return r.world().sector(SITE).efficiency() - 40;
    }

    @Test
    void itBuildsOnlyWhatTheSectorCanPayFor() {
        World poor = world("city", 40, 127, 100, Map.of("civ", 400.0, "food", 500.0, "lcm", 12.0, "hcm", 500.0));
        CommandResult r = EX.execute(poor, 0, new Command.Work(1, 0));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().sector(SITE).efficiency()).as("twelve lcm at 1 a point is twelve points").isEqualTo(40 + 12);
        assertThat(r.info()).contains("would have done more with lcm");

        World empty = world("city", 40, 127, 100, Map.of("civ", 400.0, "food", 500.0));
        assertThat(EX.execute(empty, 0, new Command.Work(1, 0)).error()).contains("not the");
    }

    @Test
    void everythingThatStopsItWorking() {
        World infantry = world();
        infantry = infantry.withUnit(new LandUnit(1, 0, "infantry", SITE, 100, Stocks.zero(COM.size()), 127, 200, 0, "", 0));
        assertThat(EX.execute(infantry, 0, new Command.Work(1, 0)).error()).contains("not an engineer");

        assertThat(EX.execute(world("city", 100, 127, 100, Map.of("lcm", 500.0, "hcm", 500.0)), 0, new Command.Work(1, 0)).error())
                .contains("is finished");
        assertThat(EX.execute(world("city", 40, 0, 100, Map.of("lcm", 500.0, "hcm", 500.0)), 0, new Command.Work(1, 0)).error())
                .contains("no mobility");
        assertThat(EX.execute(world(), 0, new Command.Work(1, 5)).error()).as("five mobility does not finish a point").contains("whole point");

        World aboard = world();
        aboard = aboard.withUnit(aboard.unit(1).withShip(3));
        assertThat(EX.execute(aboard, 0, new Command.Work(1, 0)).error()).contains("aboard");
    }
}
