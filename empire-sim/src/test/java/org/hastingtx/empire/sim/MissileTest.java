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

/** Issue #71: missiles — KNOWN laun.c, mslsub.c (msl_launch, msl_hit, msl_abm_intercept), aircombat.c sam_intercept. */
class MissileTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord FIELD = Hex.stepRaw(CAP, 0, 2);
    private static final Coord THEIRS = Hex.stepRaw(CAP, 0, 3);
    private static final Coord THEIR_FIELD = Hex.stepRaw(CAP, 0, 4);
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int SHELL = COM.index("shell");

    private static World world(boolean war) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0, "shell", 100.0));
        w = w.withCountry(w.country(0).withCash(100000).withLevels(new Levels(400, 0, 0, 0)));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", THEIRS, 100000, 640, new Levels(400, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = TestWorlds.own(w, CFG, FIELD, "airfield", 100, 127, Map.of("civ", 300.0, "food", 500.0, "pet", 500.0, "shell", 500.0), Map.of());
        w = w.withSector(w.sector(THEIRS).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)).withOwner(1).withDesignation("agribusiness", 100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 400.0, "food", 900.0)))));
        w = w.withSector(w.sector(THEIR_FIELD).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)).withOwner(1).withDesignation("airfield", 100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 300.0, "food", 500.0, "pet", 500.0)))));
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        return w;
    }

    private static World plane(World w, long id, int owner, String cls, Coord at) { return w.withPlane(new Plane(id, owner, cls, at, 100, 400, 0, "")); }

    @Test
    void aMissileStrikesASectorFromAnySectorOfYoursAndIsSpent() {
        World w = plane(world(true), 1, 0, "srbm", CAP);   // not an airfield: a missile is VTOL
        CommandResult r = null;
        for (int u = 0; u < 10 && (r == null || r.info().contains("blew up")); u++) r = EX.execute(w.withUpdateNumber(u), 0, new Command.Launch(1, THEIRS, 0));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).contains("it hit");
        assertThat(r.world().plane(1)).as("spent").isNull();
        assertThat(r.world().sector(THEIRS).stock().get(COM.food)).isLessThan(900);
        assertThat(r.world().sector(CAP).stock().get(SHELL)).as("its warhead off its base").isLessThan(100);
    }

    @Test
    void whatCannotBeLaunched() {
        World w = plane(plane(plane(world(true), 1, 0, "srbm", CAP), 2, 0, "sam", CAP), 3, 0, "abm", CAP);
        assertThat(EX.execute(w, 0, new Command.Launch(2, THEIRS, 0)).error()).contains("is not launched");
        assertThat(EX.execute(w, 0, new Command.Launch(3, THEIRS, 0)).error()).contains("is not launched");
        assertThat(EX.execute(plane(world(false), 1, 0, "srbm", CAP), 0, new Command.Launch(1, THEIRS, 0)).error()).as("at peace").contains("only at war");
        assertThat(EX.execute(w, 0, new Command.Launch(1, FIELD, 0)).error()).contains("is yours");
        assertThat(EX.execute(w, 0, new Command.Launch(1, new Coord(2, 2), 0)).error()).contains("belongs to nobody");
        assertThat(EX.execute(w, 0, new Command.Bomb(1, THEIRS, false)).error()).as("not flown").contains("is a missile");
        assertThat(EX.execute(plane(world(true), 4, 0, "bomber", FIELD), 0, new Command.Launch(4, THEIRS, 0)).error()).contains("not a missile");
    }

    @Test
    void theirAbmsMeetAMissileAndAreSpent() {
        int down = 0;
        for (int u = 0; u < 20; u++) {
            World w = plane(plane(plane(world(true), 1, 0, "srbm", CAP), 2, 1, "abm", THEIR_FIELD), 3, 1, "abm", THEIR_FIELD).withUpdateNumber(u);
            CommandResult r = EX.execute(w, 0, new Command.Launch(1, THEIRS, 0));
            if (r.info().contains("blew up on launch")) continue;
            assertThat(r.info()).contains("ABM");
            if (r.info().contains("shot it down")) down++;
            assertThat(r.world().planes().stream().filter(p -> p.owner() == 1).count()).as("each that rose is spent").isLessThan(2);
        }
        assertThat(down).as("two Patriots against an Atlas bring it down often").isGreaterThan(0);
    }

    @Test
    void anAntiShipMissileGoesAtAShip() {
        World w = plane(world(true), 1, 0, "harpoon", FIELD);
        var dc = CFG.units().ships().shipClass("destroyer");
        Coord sea = Hex.stepRaw(FIELD, 1, 1), far = Hex.stepRaw(CAP, 0, 6);   // one beside your airfield, one out of sight
        World hidden = w.withShip(new Ship(9, 1, "destroyer", "", far, 100, Stocks.zero(COM.size()), null, null, 0, "", 100, null, null, dc.tankOr0(), dc.crewOr0()));
        assertThat(EX.execute(hidden, 0, new Command.Launch(1, null, 9)).error()).as("no oracle").isEqualTo("no ship #9 in sight to fire on");
        assertThat(EX.execute(hidden, 0, new Command.Launch(1, null, 77)).error()).isEqualTo("no ship #77 in sight to fire on");
        assertThat(EX.execute(hidden, 0, new Command.Launch(1, far, 0)).error()).contains("not in sight");
        w = w.withShip(new Ship(9, 1, "destroyer", "", sea, 100, Stocks.zero(COM.size()), null, null, 0, "", 100, null, null, dc.tankOr0(), dc.crewOr0()));
        assertThat(EX.execute(w, 0, new Command.Launch(1, THEIRS, 0)).error()).contains("no enemy ship in sight");
        assertThat(EX.execute(w, 0, new Command.Launch(1, sea, 0)).error()).as("a sector you see names her").isNull();
        int hits = 0;
        for (int u = 0; u < 20; u++) {
            CommandResult r = EX.execute(w.withUpdateNumber(u), 0, new Command.Launch(1, null, 9));
            assertThat(r.error()).as(r.error()).isNull();
            if (r.info().contains("hit ship #9")) { hits++; assertThat(r.world().ship(9) == null || r.world().ship(9).efficiency() < 100).isTrue(); }
        }
        assertThat(hits).isGreaterThan(0);
    }

    @Test
    void aSamRisesAgainstACostlyBomberOnly() {
        World w = plane(plane(world(true), 1, 0, "bomber", FIELD), 2, 1, "sam", THEIR_FIELD);
        CommandResult r = EX.execute(w, 0, new Command.Bomb(1, Hex.stepRaw(CAP, 0, 4), false));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).contains("a SAM of Them rose against #1");
        assertThat(r.world().plane(2)).as("a SAM is spent").isNull();
        World cheap = plane(plane(world(true), 1, 0, "recon", FIELD), 2, 1, "sam", THEIR_FIELD);   // $1800 recon, but a fighter_1 at $400 would not
        World fighter = plane(plane(world(true), 1, 0, "fighter_1", FIELD), 2, 1, "sam", THEIR_FIELD);
        assertThat(EX.execute(fighter, 0, new Command.Recon(1, Hex.stepRaw(CAP, 0, 4))).error()).isNull();
        assertThat(EX.execute(fighter, 0, new Command.Recon(1, Hex.stepRaw(CAP, 0, 4))).info()).doesNotContain("SAM");
        assertThat(cheap).isNotNull();
        CommandResult guard = EX.execute(plane(world(true), 2, 0, "sam", FIELD), 0, new Command.AirMission(2, FIELD, 2, false));
        assertThat(guard.error()).as("a SAM may be set on air defence").isNull();
    }
}
