package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #262 (#71 slice 3a): planes. KNOWN plane.config, commands/buil.c, subs/plnsub.c {@code pln_equip} and
 * {@code pln_damage}, subs/aircombat.c flak.
 */
class PlaneTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord FIELD = Hex.stepRaw(CAP, 0, 2);      // our airfield on the rim
    private static final Coord THEIRS = Hex.stepRaw(CAP, 0, 3);
    private static final Coord FAR = new Coord(2, 2);
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int PET = COM.index("pet"), SHELL = COM.index("shell"), GUN = COM.index("gun");

    /** Us with an airfield; Them next door with the flak named, at war or not. */
    private static World world(boolean war, double petrol, double shells, double theirGuns) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = w.withCountry(w.country(0).withCash(100000).withLevels(new Levels(200, 0, 0, 0)));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", THEIRS, 100000, 640, new Levels(200, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = TestWorlds.own(w, CFG, FIELD, "airfield", 100, 127, Map.of("civ", 300.0, "food", 500.0, "pet", petrol, "shell", shells, "lcm", 200.0, "hcm", 200.0), Map.of());
        w = w.withSector(w.sector(THEIRS).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0))
                .withOwner(1).withDesignation("agribusiness", 100).withMobility(100).withRoadLevel(80)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 400.0, "food", 900.0, "gun", theirGuns)))));
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        return w;
    }

    private static World withPlane(World w, String cls) {
        return w.withPlane(new Plane(w.nextPlaneId(), 0, cls, FIELD, 100, 200, 0, ""));
    }

    private static World world() { return withPlane(world(true, 100, 100, 0), "bomber"); }

    @Test
    void aPlaneIsLaidDownOnAnAirfieldAtATenth() {
        World w = world(true, 100, 100, 0);
        CommandResult r = EX.execute(w, 0, new Command.BuildPlane(FIELD, "bomber"));
        assertThat(r.error()).as(r.error()).isNull();
        Plane p = r.world().planes().get(0);
        assertThat(p.efficiency()).isEqualTo(10);
        assertThat(p.cls()).isEqualTo("bomber");
        assertThat(r.world().country(0).cash()).as("a tenth of $3000").isEqualTo(100000 - 300);

        assertThat(EX.execute(w, 0, new Command.BuildPlane(CAP, "bomber")).error()).contains("airfield");
        assertThat(EX.execute(w, 0, new Command.BuildPlane(FIELD, "zeppelin")).error()).contains("unknown plane class");
        World low = w.withCountry(w.country(0).withLevels(new Levels(50, 0, 0, 0)));
        assertThat(EX.execute(low, 0, new Command.BuildPlane(FIELD, "bomber")).error()).contains("needs tech 90");
    }

    @Test
    void itFitsOutOnItsFieldAndCostsItsUpkeep() {
        World w = world(true, 100, 100, 0);
        w = w.withPlane(new Plane(1, 0, "bomber", FIELD, 10, 200, 0, ""));
        World after = Update.run(w, CFG, 3).next();
        assertThat(after.plane(1).efficiency()).as("fitted out on the field").isGreaterThan(10);
        assertThat(after.plane(1).note()).contains("fitted out");
        // the same update without it earns more: the difference is its upkeep
        World none = Update.run(world(true, 100, 100, 0), CFG, 3).next();
        assertThat(after.country(0).cash()).as("a plane costs its keep").isLessThan(none.country(0).cash());
    }

    @Test
    void aSortieTakesPetrolAndShellsOffTheFieldAndWrecksTheTarget() {
        World w = world();
        Sector before = w.sector(THEIRS);
        CommandResult r = EX.execute(w, 0, new Command.Bomb(1, THEIRS, false));
        assertThat(r.error()).as(r.error()).isNull();
        Sector after = r.world().sector(THEIRS);
        assertThat(after.efficiency()).as("the sector is bombed").isLessThan(before.efficiency());
        assertThat(after.stock().get(COM.food)).isLessThan(before.stock().get(COM.food));
        assertThat(r.world().sector(FIELD).stock().get(PET)).as("KNOWN pl_fuel: three petrol for a bomber's sortie").isEqualTo(97);
        assertThat(r.world().sector(FIELD).stock().get(SHELL)).as("and its bombs").isLessThan(100);
        assertThat(r.world().plane(1).at()).as("it came home").isEqualTo(FIELD);
        assertThat(after.owner()).as("bombing takes no ground").isEqualTo(1);
    }

    /**
     * KNOWN pln_damage: the damage doubles when the plane is the right kind for the raid — a bomber flying a
     * strategic one, a tactical bomber flying a pinpoint one. The same plane on the wrong sort of raid does half.
     */
    @Test
    void theRightRaidForThePlaneDoesTwiceTheDamage() {
        double bomberStrategic = 0, bomberPinpoint = 0, tacticalStrategic = 0, tacticalPinpoint = 0;
        for (long seed = 1; seed <= 25; seed++) {
            CommandExecutor ex = new CommandExecutor(TestWorlds.teaching(seed));
            bomberStrategic += wrecked(ex, withPlane(world(true, 100, 100, 0), "bomber"), false);
            bomberPinpoint += wrecked(ex, withPlane(world(true, 100, 100, 0), "bomber"), true);
            tacticalStrategic += wrecked(ex, withPlane(world(true, 100, 100, 0), "tactical_bomber"), false);
            tacticalPinpoint += wrecked(ex, withPlane(world(true, 100, 100, 0), "tactical_bomber"), true);
        }
        assertThat(bomberStrategic).as("a bomber is built for strategic raids: " + bomberStrategic + " against " + bomberPinpoint)
                .isGreaterThan(bomberPinpoint * 1.3);
        assertThat(tacticalPinpoint).as("a tactical bomber for pinpoint ones: " + tacticalPinpoint + " against " + tacticalStrategic)
                .isGreaterThan(tacticalStrategic * 1.3);
    }

    private static double wrecked(CommandExecutor ex, World w, boolean pinpoint) {
        double before = w.sector(THEIRS).efficiency();
        return before - ex.execute(w, 0, new Command.Bomb(1, THEIRS, pinpoint)).world().sector(THEIRS).efficiency();
    }

    /** KNOWN ac_flak_dam: guns over the target hurt every sortie, and a battered plane does not come home. */
    @Test
    void flakHurtsAndFinishesABatteredPlane() {
        int hit = 0, hurt = 0;
        for (long seed = 1; seed <= 30; seed++) {
            CommandExecutor ex = new CommandExecutor(TestWorlds.teaching(seed));
            World w = withPlane(world(true, 100, 100, 40), "bomber");
            CommandResult r = ex.execute(w, 0, new Command.Bomb(1, THEIRS, false));
            assertThat(r.error()).as(r.error()).isNull();
            if (r.info().contains("flak")) hit++;
            if (r.world().plane(1) != null && r.world().plane(1).efficiency() < 100) hurt++;
        }
        assertThat(hit).as("their guns fired every time").isEqualTo(30);
        assertThat(hurt).as("and every sortie came home the worse for it").isGreaterThan(20);

        int lost = 0;
        for (long seed = 1; seed <= 20; seed++) {
            CommandExecutor ex = new CommandExecutor(TestWorlds.teaching(seed));
            World w = withPlane(world(true, 100, 100, 40), "bomber");
            w = w.withPlane(w.plane(1).withEfficiency(15));
            if (ex.execute(w, 0, new Command.Bomb(1, THEIRS, false)).world().plane(1) == null) lost++;
        }
        assertThat(lost).as("a plane already shot up does not come back: " + lost).isGreaterThan(0);

        World quiet = withPlane(world(true, 100, 100, 0), "bomber");
        assertThat(EX.execute(quiet, 0, new Command.Bomb(1, THEIRS, false)).info()).as("no guns, no flak").doesNotContain("flak");
    }

    @Test
    void reconPutsTheSectorOnYourChart() {
        World w = withPlane(world(true, 100, 100, 0), "recon");
        CommandResult r = EX.execute(w, 0, new Command.Recon(1, THEIRS));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).contains("flew over").contains("agribusiness");
        assertThat(r.world().seen().stream().anyMatch(s -> s.owner() == 0 && s.at().equals(THEIRS))).as("on our chart now").isTrue();
        assertThat(r.world().sector(FIELD).stock().get(SHELL)).as("reconnaissance drops nothing").isEqualTo(100);
    }

    @Test
    void everythingThatStopsASortie() {
        assertThat(EX.execute(withPlane(world(false, 100, 100, 0), "bomber"), 0, new Command.Bomb(1, THEIRS, false)).error())
                .as("bombing a country you are not at war with").contains("war");
        assertThat(EX.execute(world(), 0, new Command.Bomb(1, FAR, false)).error()).contains("hexes off");
        assertThat(EX.execute(world(), 0, new Command.Bomb(1, FIELD, false)).error()).contains("is yours");
        assertThat(EX.execute(withPlane(world(true, 1, 100, 0), "bomber"), 0, new Command.Bomb(1, THEIRS, false)).error()).contains("petrol");
        assertThat(EX.execute(withPlane(world(true, 100, 0, 0), "bomber"), 0, new Command.Bomb(1, THEIRS, false)).error()).contains("no shells");
        assertThat(EX.execute(withPlane(world(true, 100, 100, 0), "recon"), 0, new Command.Bomb(1, THEIRS, false)).error()).contains("carries no bombs");
        World wreck = world();
        wreck = wreck.withPlane(wreck.plane(1).withEfficiency(5));
        assertThat(EX.execute(wreck, 0, new Command.Bomb(1, THEIRS, false)).error()).contains("wreckage");
    }
}
