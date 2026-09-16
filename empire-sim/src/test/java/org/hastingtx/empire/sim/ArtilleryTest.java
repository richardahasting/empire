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
 * Issue #256 (#71 slice 2d): artillery, as the original fired it (subs/landgun.c {@code lnd_fire} and
 * {@code landunitgun}, include/land.h {@code LAND_MINFIREEFF}).
 */
class ArtilleryTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord OURS = Hex.stepRaw(CAP, 0, 2);
    private static final Coord THEIRS = Hex.stepRaw(CAP, 0, 3);
    private static final Coord FAR = Hex.stepRaw(CAP, 0, 9);
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int GUN = COM.index("gun"), SHELL = COM.index("shell");

    /** Us on the disc with a battery at OURS; Them holding THEIRS and a sector far away, at war or not. */
    private static World world(boolean war, String cls, double guns, double shells, double eff) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", THEIRS, 100000, 640, new Levels(200, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = w.withCountry(w.country(0).withLevels(new Levels(200, 0, 0, 0)));
        w = TestWorlds.own(w, CFG, OURS, "agribusiness", 100, 127, Map.of("civ", 300.0, "food", 500.0), Map.of());
        for (Coord at : new Coord[] {THEIRS, FAR}) {
            w = w.withSector(w.sector(at).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0))
                    .withOwner(1).withDesignation("agribusiness", 100).withMobility(100).withRoadLevel(80)
                    .withStock(Stocks.of(COM.fromMap(Map.of("civ", 400.0, "food", 900.0)))));
        }
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        LandUnit u = new LandUnit(w.nextUnitId(), 0, cls, OURS, eff,
                Stocks.of(COM.fromMap(Map.of("mil", 25.0, "gun", guns, "shell", shells, "food", 24.0))), 100, 200, 0, "", 0);
        return w.withUnit(u);
    }

    private static World world() { return world(true, "artillery", 10, 40, 100); }

    @Test
    void aBatteryWrecksWhatTheSectorHadAndSpendsItsShells() {
        World w = world();
        Sector before = w.sector(THEIRS);
        CommandResult r = EX.execute(w, 0, new Command.UnitFire(1, THEIRS));
        assertThat(r.error()).as(r.error()).isNull();
        Sector after = r.world().sector(THEIRS);
        assertThat(after.efficiency()).as("the sector is knocked about").isLessThan(before.efficiency());
        assertThat(after.stock().get(COM.food)).as("and so is what it held").isLessThan(before.stock().get(COM.food));
        assertThat(after.roadLevel()).isLessThan(before.roadLevel());
        assertThat(r.world().unit(1).stock().get(SHELL)).as("KNOWN l_ammo: three shells a salvo").isEqualTo(37);
        assertThat(after.owner()).as("shelling takes no ground").isEqualTo(1);
    }

    /** KNOWN landunitgun: (4 + roll(6)) a gun, by efficiency — so more guns hurt more, and a worn battery hurts less. */
    @Test
    void moreGunsAndBetterConditionHurtMore() {
        double few = 0, many = 0, worn = 0;
        for (long seed = 1; seed <= 20; seed++) {
            CommandExecutor ex = new CommandExecutor(TestWorlds.teaching(seed));
            few += lost(ex, world(true, "artillery", 2, 40, 100));
            many += lost(ex, world(true, "artillery", 10, 40, 100));
            worn += lost(ex, world(true, "artillery", 10, 40, 45));
        }
        assertThat(many).as("ten guns against two: " + many + " vs " + few).isGreaterThan(few);
        assertThat(worn).as("a worn battery: " + worn + " against " + many).isLessThan(many);
    }

    private static double lost(CommandExecutor ex, World w) {
        double before = w.sector(THEIRS).efficiency();
        return before - ex.execute(w, 0, new Command.UnitFire(1, THEIRS)).world().sector(THEIRS).efficiency();
    }

    /** KNOWN lnd_fire: short of a full salvo's ammunition, it fires what it has for proportionally less. */
    @Test
    void oneShellIsAThirdOfASalvo() {
        double full = 0, scant = 0;
        for (long seed = 1; seed <= 30; seed++) {
            CommandExecutor ex = new CommandExecutor(TestWorlds.teaching(seed));
            full += lost(ex, world(true, "artillery", 10, 3, 100));
            scant += lost(ex, world(true, "artillery", 10, 1, 100));
        }
        assertThat(scant).as("a third of a salvo does about a third of the damage: " + scant + " against " + full).isLessThan(full * 0.6);
        World last = world(true, "artillery", 10, 1, 100);
        assertThat(EX.execute(last, 0, new Command.UnitFire(1, THEIRS)).world().unit(1).stock().get(SHELL))
                .as("it fired the one it had").isZero();
    }

    @Test
    void everythingThatStopsItFiring() {
        assertThat(EX.execute(world(false, "artillery", 10, 40, 100), 0, new Command.UnitFire(1, THEIRS)).error())
                .as("shelling a country you are not at war with").contains("war");
        assertThat(EX.execute(world(true, "infantry", 0, 0, 100), 0, new Command.UnitFire(1, THEIRS)).error()).contains("carries no guns");
        assertThat(EX.execute(world(true, "artillery", 10, 40, 30), 0, new Command.UnitFire(1, THEIRS)).error()).contains("guns need 40%");
        assertThat(EX.execute(world(true, "artillery", 0, 40, 100), 0, new Command.UnitFire(1, THEIRS)).error()).contains("no guns aboard");
        assertThat(EX.execute(world(true, "artillery", 10, 0, 100), 0, new Command.UnitFire(1, THEIRS)).error()).contains("no shells");
        assertThat(EX.execute(world(), 0, new Command.UnitFire(1, FAR)).error()).contains("hexes away");
        assertThat(EX.execute(world(), 0, new Command.UnitFire(1, OURS)).error()).contains("is yours");
        World aboard = world();
        aboard = aboard.withUnit(aboard.unit(1).withShip(7));
        assertThat(EX.execute(aboard, 0, new Command.UnitFire(1, THEIRS)).error()).contains("aboard");
    }
}
