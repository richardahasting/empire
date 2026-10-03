package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.geo.Orbit;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.view.CountryView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #71: satellites — KNOWN laun.c launch_sat and launch_as, sate.c, satmap.c, move_sat.c. */
class SatelliteTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord FIELD = Hex.stepRaw(CAP, 0, 2);
    private static final Coord THEIRS = Hex.stepRaw(CAP, 0, 3);
    private static final Coord THEIR_FIELD = Hex.stepRaw(CAP, 0, 4);
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int PET = COM.index("pet");

    private static World world(boolean war) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = w.withCountry(w.country(0).withCash(100000).withLevels(new Levels(400, 0, 0, 0)));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", THEIRS, 100000, 640, new Levels(400, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = TestWorlds.own(w, CFG, FIELD, "airfield", 100, 127, Map.of("civ", 300.0, "food", 500.0, "pet", 500.0), Map.of());
        w = w.withSector(w.sector(THEIRS).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)).withOwner(1).withDesignation("agribusiness", 100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 400.0, "food", 900.0)))));
        w = w.withSector(w.sector(THEIR_FIELD).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)).withOwner(1).withDesignation("airfield", 100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 300.0, "food", 500.0, "pet", 500.0)))));
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        return w;
    }

    private static World plane(World w, long id, int owner, String cls, Coord at) { return w.withPlane(new Plane(id, owner, cls, at, 100, 400, 0, "")); }

    /** Launch until the booster holds (it may blow up on the pad, as the original's may). */
    private static CommandResult up(World w, Command.Launch l) {
        CommandResult r = null;
        for (int u = 0; u < 20; u++) {
            r = EX.execute(w.withUpdateNumber(u), 0, l);
            assertThat(r.error()).as(r.error()).isNull();
            if (r.world().plane(l.missile()) != null) return r;
        }
        return r;
    }

    @Test
    void aSatelliteGoesUpOnPetrolAndReportsFromTheNextUpdate() {
        World w = plane(world(false), 1, 0, "landsat", FIELD);
        CommandResult r = up(w, new Command.Launch(1, THEIRS, 0, false));
        Plane sat = r.world().plane(1);
        assertThat(sat.orbiting()).isTrue();
        assertThat(sat.orbit()).isEqualTo(Plane.ORBIT);
        assertThat(Hex.distance(r.world(), sat.at(), THEIRS)).as("on target, or a sector astray").isLessThanOrEqualTo(1);
        assertThat(r.world().sector(FIELD).stock().get(PET)).as("the booster's petrol").isLessThan(500);
        assertThat(r.info()).contains("in orbit over");

        World upNow = r.world();
        assertThat(EX.execute(upNow, 0, new Command.Satellite(1)).error()).contains("went up this update");
        assertThat(EX.execute(upNow, 0, new Command.Launch(1, THEIRS, 0)).error()).contains("already in orbit");
        assertThat(EX.execute(upNow, 0, new Command.Bomb(1, THEIRS, false)).error()).contains("is a satellite");
        assertThat(EX.execute(upNow, 0, new Command.Fly(List.of(1L), FIELD, null, List.of())).error()).contains("satellite");

        // a landsat charts the sea, not the land under it (the original marks the rest '?')
        World next = upNow.withUpdateNumber(upNow.updateNumber() + 1);
        CommandResult rep = EX.execute(next, 0, new Command.Satellite(1));
        assertThat(rep.error()).as(rep.error()).isNull();
        List<SeenSector> mine = rep.world().seen().stream().filter(s -> s.owner() == 0).toList();
        assertThat(mine).isNotEmpty();
        assertThat(mine).allMatch(s -> s.terrain() == Terrain.OCEAN || s.terrain() == Terrain.MOUNTAIN);
        assertThat(rep.info()).doesNotContain("Them's");

        CountryView v = CountryView.of(rep.world(), CFG, 0);
        var pv = v.planes().stream().filter(p -> p.id() == 1).findFirst().orElseThrow();
        assertThat(pv.satellite()).isTrue();
        assertThat(pv.ready()).isTrue();
    }

    @Test
    void aSpySatelliteReportsTheirSectorsShipsAndUnits() {
        World w = plane(world(false), 1, 0, "spysat", FIELD);
        var dc = CFG.units().ships().shipClass("destroyer");
        Coord sea = Hex.stepRaw(CAP, 0, 6);
        w = w.withShip(new Ship(9, 1, "destroyer", "", sea, 100, Stocks.zero(COM.size()), null, null, 0, "", 100, null, null, dc.tankOr0(), dc.crewOr0()));
        World up = up(w, new Command.Launch(1, THEIRS, 0, true)).world();
        assertThat(up.plane(1).orbit()).isEqualTo(Plane.GEOSYNC);
        CommandResult rep = EX.execute(up.withUpdateNumber(up.updateNumber() + 1), 0, new Command.Satellite(1));
        assertThat(rep.error()).as(rep.error()).isNull();
        assertThat(rep.info()).contains("Them's agribusiness at " + THEIRS).contains("civ 400").contains("destroyer #9");
        assertThat(rep.world().seen()).anyMatch(s -> s.owner() == 0 && s.at().equals(THEIRS) && s.sectorOwner() == 1);
        assertThat(rep.world().contactsOf(0)).anyMatch(k -> k.shipId() == 9);
    }

    @Test
    void anOrbitMovesEachUpdateAndAGeostationaryOneStays() {
        var mc = CFG.units().planes().missiles();
        World w = world(false);
        Plane p = new Plane(1, 0, "landsat", THEIRS, 100, 400, 0, "").inOrbit(THEIRS, false, 0);
        Coord was = p.at();
        boolean moved = false;
        for (int i = 0; i < 40; i++) {
            p = Orbit.next(mc, w, p);
            assertThat(w.inBounds(p.at())).isTrue();
            moved |= !p.at().equals(was);
        }
        assertThat(moved).isTrue();
        assertThat(p.theta()).isBetween(0.0, 1.0);

        World both = w.withPlane(new Plane(1, 0, "landsat", THEIRS, 100, 400, 0, "").inOrbit(THEIRS, false, 0))
                      .withPlane(new Plane(2, 0, "landsat", THEIRS, 100, 400, 0, "").inOrbit(THEIRS, true, 0));
        World n = Update.run(both, CFG, 3).next();
        assertThat(n.plane(1).theta()).isGreaterThan(0);
        assertThat(n.plane(2).at()).isEqualTo(THEIRS);
        assertThat(n.plane(2).theta()).isZero();
    }

    @Test
    void anAntiSatShootsDownASatelliteYouCanSee() {
        Coord near = Hex.stepRaw(FIELD, 1, 1);    // beside your field: in sight
        Coord far = Hex.stepRaw(CAP, 3, 8);       // out of sight
        World w = plane(world(true), 1, 0, "asat", FIELD)
                .withPlane(new Plane(5, 1, "spysat", near, 100, 320, 0, "").inOrbit(near, true, 0))
                .withPlane(new Plane(6, 1, "spysat", far, 100, 320, 0, "").inOrbit(far, true, 0));
        assertThat(EX.execute(w, 0, new Command.Launch(1, far, 0)).error()).isEqualTo("no enemy satellite in sight over " + far);
        assertThat(EX.execute(w, 0, new Command.Launch(1, THEIRS, 0)).error()).isEqualTo("no enemy satellite in sight over " + THEIRS);
        World peace = plane(world(false), 1, 0, "asat", FIELD).withPlane(new Plane(5, 1, "spysat", near, 100, 320, 0, "").inOrbit(near, true, 0));
        assertThat(EX.execute(peace, 0, new Command.Launch(1, near, 0)).error()).as("at peace").contains("no enemy satellite");
        assertThat(CountryView.of(w, CFG, 0).overhead()).extracting(CountryView.OverheadView::at).containsExactly(near);
        int down = 0;
        for (int u = 0; u < 20; u++) {
            CommandResult r = EX.execute(w.withUpdateNumber(u), 0, new Command.Launch(1, near, 0));
            assertThat(r.error()).as(r.error()).isNull();
            assertThat(r.world().plane(1)).as("spent").isNull();
            if (r.info().contains("shot down")) { down++; assertThat(r.world().plane(5)).isNull(); }
        }
        assertThat(down).isGreaterThan(0);
    }

    @Test
    void theirAntiSatsRiseAgainstOnePutUpOverThem() {
        int met = 0;
        for (int u = 0; u < 20; u++) {
            World w = plane(plane(world(true), 1, 0, "landsat", FIELD), 2, 1, "asat", THEIR_FIELD).withUpdateNumber(u);
            CommandResult r = EX.execute(w, 0, new Command.Launch(1, THEIRS, 0, false));
            if (r.info().contains("booster blew up")) continue;
            if (r.info().contains("anti-sat")) { met++; assertThat(r.world().plane(2)).as("spent").isNull(); }
        }
        assertThat(met).isGreaterThan(0);
    }

    @Test
    void aSatelliteUpThereIsNotFittedOutByTheFieldUnderIt() {
        World w = world(true).withPlane(new Plane(5, 1, "landsat", THEIR_FIELD, 90, 320, 0, "").inOrbit(THEIR_FIELD, true, 0));
        World n = Update.run(w, CFG, 3).next();
        assertThat(n.plane(5).efficiency()).as("no field fits it out up there").isEqualTo(90);
        assertThat(n.plane(5).orbiting()).isTrue();
        // nor sold: there is nowhere to hand it over
        World mine = world(false).withPlane(new Plane(5, 0, "landsat", THEIRS, 100, 320, 0, "").inOrbit(THEIRS, true, 0));
        assertThat(EX.execute(mine, 0, new Command.SetPrice("plane", List.of(5L), 5000)).error()).contains("is in orbit; it cannot be sold");
    }
}
