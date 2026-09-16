package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #260 (#71 slice 2f): supporting fire. KNOWN subs/lndsub.c {@code lnd_support} and subs/attsub.c
 * {@code get_osupport}/{@code get_dsupport}: batteries within range of the sector being fought over fire, and the
 * damage they do multiplies that side's strength.
 */
class SupportingFireTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord FRONT = Hex.stepRaw(CAP, 0, 2);      // ours, next to the target
    private static final Coord BATTERY = Hex.stepRaw(CAP, 1, 1);    // ours, behind the line
    private static final Coord TARGET = Hex.stepRaw(CAP, 0, 3);     // theirs
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int SHELL = COM.index("shell");

    /** Us beside Them, at war; TARGET held by the garrison named. */
    private static World war(double targetMil) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", TARGET, 100000, 640, new Levels(200, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = w.withCountry(w.country(0).withLevels(new Levels(200, 0, 0, 0)));
        w = w.withSector(w.sector(TARGET).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0))
                .withOwner(1).withDesignation("agribusiness", 100).withMobility(100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 300.0, "mil", targetMil, "food", 500.0)))));
        w = TestWorlds.own(w, CFG, FRONT, "agribusiness", 100, 127, Map.of("civ", 300.0, "mil", 300.0, "food", 500.0), Map.of());
        w = TestWorlds.own(w, CFG, BATTERY, "agribusiness", 100, 127, Map.of("civ", 300.0, "food", 500.0), Map.of());
        return w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
    }

    private static World battery(World w, int owner, Coord at, double shells) {
        return w.withUnit(new LandUnit(w.nextUnitId(), owner, "artillery", at, 100,
                Stocks.of(COM.fromMap(Map.of("mil", 25.0, "gun", 10.0, "shell", shells, "food", 24.0))), 100, 200, 0, "", 0));
    }

    private static Command.Attack attack(int men) {
        return new Command.Attack(TARGET, List.of(new Command.Attack.Party(FRONT, men)));
    }

    @Test
    void aBatteryBehindTheLineFiresAndTellsYouSo() {
        World w = battery(war(40), 0, BATTERY, 40);
        CommandResult r = EX.execute(w, 0, attack(60));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).contains("your batteries #1 fired in support");
        assertThat(r.world().unit(1).stock().get(SHELL)).as("it spent a salvo").isEqualTo(37);
        assertThat(r.world().unit(1).at()).as("it supported from where it stood").isEqualTo(BATTERY);
    }

    /** The point of it: the same attack that fails alone succeeds with guns behind it. */
    @Test
    void supportWinsAnAttackThatWouldHaveFailed() {
        int without = 0, with = 0;
        for (long seed = 1; seed <= 20; seed++) {
            CommandExecutor ex = new CommandExecutor(TestWorlds.teaching(seed));
            if (ex.execute(war(60), 0, attack(60)).world().sector(TARGET).owner() == 0) without++;
            if (ex.execute(battery(war(60), 0, BATTERY, 40), 0, attack(60)).world().sector(TARGET).owner() == 0) with++;
        }
        assertThat(with).as("sixty against sixty, with guns " + with + " times against " + without).isGreaterThan(without);
    }

    /** And it cuts both ways: their battery behind their line makes the same attack harder. */
    @Test
    void theirBatterySupportsTheDefence() {
        int mine = 0, theirs = 0;
        for (long seed = 1; seed <= 20; seed++) {
            CommandExecutor ex = new CommandExecutor(TestWorlds.teaching(seed));
            if (ex.execute(battery(war(60), 0, BATTERY, 40), 0, attack(60)).world().sector(TARGET).owner() == 0) mine++;
            World both = battery(battery(war(60), 0, BATTERY, 40), 1, Hex.stepRaw(CAP, 0, 4), 40);
            if (ex.execute(both, 0, attack(60)).world().sector(TARGET).owner() == 0) theirs++;
        }
        assertThat(theirs).as("their guns answering: " + theirs + " against " + mine).isLessThan(mine);
    }

    @Test
    void aBatteryInTheSectorItselfDoesNotSupportAndOneOutOfRangeCannotReach() {
        World inside = battery(war(40), 0, FRONT, 40);       // FRONT is a party to the attack, not support
        CommandResult r = EX.execute(inside, 0, new Command.Attack(TARGET, List.of(new Command.Attack.Party(FRONT, 60)), List.of()));
        assertThat(r.info()).as("a battery in the sector sending the men still shoots from behind it").contains("support");

        World far = battery(war(40), 0, Hex.stepRaw(CAP, 3, 3), 40);
        World out = far.withUnit(far.unit(1).withAt(new Coord(2, 2)));
        assertThat(EX.execute(out, 0, attack(60)).info()).as("out of range").doesNotContain("support");

        World dry = battery(war(40), 0, BATTERY, 0);
        assertThat(EX.execute(dry, 0, attack(60)).info()).as("no shells").doesNotContain("support");
    }
}
