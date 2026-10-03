package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.view.CountryView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #71: nuclear warheads — KNOWN buil.c build_nuke, arm.c, subs/detonate.c, subs/damage.c nukedamage. */
class NukeTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord PLANT = Hex.stepRaw(CAP, 3, 1);
    private static final Coord FIELD = Hex.stepRaw(CAP, 0, 2);
    private static final Coord THEIRS = Hex.stepRaw(CAP, 0, 5);
    private static final Coord THEIR_FIELD = Hex.stepRaw(CAP, 0, 6);
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    private static World world(boolean war) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0, "shell", 100.0));
        w = w.withCountry(w.country(0).withCash(500000).withLevels(new Levels(400, 0, 0, 0)));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", THEIRS, 100000, 640, new Levels(400, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = TestWorlds.own(w, CFG, PLANT, "nuclear_plant", 100, 127, Map.of("civ", 300.0, "food", 500.0, "lcm", 500.0, "hcm", 500.0, "oil", 500.0, "rad", 500.0), Map.of());
        w = TestWorlds.own(w, CFG, FIELD, "airfield", 100, 127, Map.of("civ", 300.0, "food", 500.0, "pet", 500.0, "shell", 500.0), Map.of());
        for (Coord c : List.of(THEIRS, THEIR_FIELD, Hex.stepRaw(THEIRS, 1, 1), Hex.stepRaw(THEIRS, 4, 1)))
            w = w.withSector(w.sector(c).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)).withOwner(1)
                    .withDesignation(c.equals(THEIR_FIELD) ? "airfield" : "agribusiness", 100)
                    .withStock(Stocks.of(COM.fromMap(Map.of("civ", 400.0, "food", 900.0, "pet", 100.0)))));
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        return w;
    }

    private static World plane(World w, long id, int owner, String cls, Coord at) { return w.withPlane(new Plane(id, owner, cls, at, 100, 400, 0, "")); }
    private static World nuke(World w, long id, String cls, Coord at) { return w.withNuke(new Nuke(id, 0, cls, at, 400, 0, 0, false)); }

    @Test
    void aWarheadIsBuiltWholeInANuclearPlant() {
        World w = world(false);
        CommandResult r = EX.execute(w, 0, new Command.BuildNuke(PLANT, "fission_10kt"));
        assertThat(r.error()).as(r.error()).isNull();
        Nuke n = r.world().nukes().get(0);
        assertThat(n.cls()).isEqualTo("fission_10kt");
        assertThat(n.at()).isEqualTo(PLANT);
        assertThat(r.world().sector(PLANT).stock().get(COM.index("rad"))).isEqualTo(430);
        assertThat(r.world().country(0).cash()).isEqualTo(490000);
        assertThat(EX.execute(w, 0, new Command.BuildNuke(FIELD, "fission_10kt")).error()).contains("nuclear plant");
        assertThat(EX.execute(w.withSector(w.sector(PLANT).withDesignation("nuclear_plant", 40)), 0, new Command.BuildNuke(PLANT, "fission_10kt")).error()).contains("60%");
        World low = w.withCountry(w.country(0).withLevels(new Levels(200, 0, 0, 0)));
        assertThat(EX.execute(low, 0, new Command.BuildNuke(PLANT, "fission_10kt")).error()).contains("needs tech 280");
        assertThat(CountryView.of(r.world(), CFG, 0).nukes()).hasSize(1);
        assertThat(CountryView.of(r.world(), CFG, 1).nukes()).isEmpty();
    }

    @Test
    void armingWantsACarrierThatCanLiftItInTheSameSector() {
        World w = nuke(plane(plane(plane(world(false), 1, 0, "bomber", FIELD), 2, 0, "fighter_1", FIELD), 3, 0, "harpoon", FIELD), 7, "fission_10kt", FIELD);
        CommandResult r = EX.execute(w, 0, new Command.Arm(1, 7, true));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().nuke(7).plane()).isEqualTo(1);
        assertThat(r.world().nuke(7).airburst()).isTrue();
        assertThat(EX.execute(w, 0, new Command.Arm(2, 7, false)).error()).as("a biplane lifts 1").contains("cannot carry a 10kt fission");
        assertThat(EX.execute(w, 0, new Command.Arm(3, 7, false)).error()).as("an anti-ship missile").contains("cannot carry a nuclear device");
        assertThat(EX.execute(nuke(plane(world(false), 1, 0, "bomber", FIELD), 7, "fission_10kt", PLANT), 0, new Command.Arm(1, 7, false)).error()).contains("not with plane #1");
        // a warhead goes where its plane goes, and with it
        World armed = r.world();
        assertThat(armed.nukeAt(armed.nuke(7))).isEqualTo(FIELD);
        assertThat(armed.withoutPlane(1).nuke(7)).as("shot down with it").isNull();
        CommandResult off = EX.execute(armed, 0, new Command.Disarm(1));
        assertThat(off.error()).as(off.error()).isNull();
        assertThat(off.world().nuke(7).plane()).isZero();
        assertThat(off.world().nuke(7).at()).isEqualTo(FIELD);
        CountryView v = CountryView.of(armed, CFG, 0);
        assertThat(v.planes().stream().filter(p -> p.id() == 1).findFirst().orElseThrow().nuke()).isEqualTo(7);
    }

    @Test
    void aNuclearMissileAlwaysHitsAndABigOneLeavesAWasteland() {
        World w = nuke(plane(plane(world(true), 1, 0, "srbm", CAP), 2, 1, "bomber", THEIR_FIELD), 7, "fusion_1mt", CAP);
        w = EX.execute(w, 0, new Command.Arm(1, 7, false)).world();
        CommandResult r = null;
        for (int u = 0; u < 20 && (r == null || r.info().contains("blew up")); u++) r = EX.execute(w.withUpdateNumber(u), 0, new Command.Launch(1, THEIRS, 0));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).contains("a groundburst of a 1mt fusion over " + THEIRS).contains("radioactive wasteland");
        World after = r.world();
        assertThat(after.nukes()).isEmpty();
        assertThat(after.plane(1)).isNull();
        Sector zero = after.sector(THEIRS);
        assertThat(zero.owned()).isFalse();
        assertThat(zero.designation()).isEqualTo("wasteland");
        assertThat(zero.stock().get(COM.civ)).isZero();
        // a sector further out is damaged, not destroyed; their bomber on the next field is caught
        Sector next = after.sector(THEIR_FIELD);
        assertThat(next.owner()).isEqualTo(1);
        assertThat(next.stock().get(COM.civ)).isLessThan(400);
        assertThat(after.plane(2) == null || after.plane(2).efficiency() < 100).isTrue();
        assertThat(r.info()).as("what it caught of theirs is not ours to know").doesNotContain("plane").doesNotContain("warhead");
    }

    @Test
    void aSanctuaryIsUntouchedAndAnArmedPlaneCarriesNothingElse() {
        // a third country still in sanctuary, its ship beside the target: the blast passes it by
        World w = nuke(plane(world(true), 1, 0, "srbm", CAP), 7, "fusion_1mt", CAP);
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(2, "Haven", new Coord(1, 1), 1000, 100, Levels.ZERO, HandicapCfg.NONE, true, false, 0));
        var dc = CFG.units().ships().shipClass("destroyer");
        Coord beside = Hex.stepRaw(THEIRS, 2, 1);
        w = w.withCountries(cs).withShip(new Ship(9, 2, "destroyer", "", beside, 100, Stocks.zero(COM.size()), null, null, 0, "", 100, null, null, dc.tankOr0(), dc.crewOr0()));
        w = EX.execute(w, 0, new Command.Arm(1, 7, false)).world();
        CommandResult r = null;
        for (int u = 0; u < 20 && (r == null || r.info().contains("blew up")); u++) r = EX.execute(w.withUpdateNumber(u), 0, new Command.Launch(1, THEIRS, 0));
        assertThat(r.world().ship(9).efficiency()).isEqualTo(100);
        // armed, a transport drops nothing and flies no mission
        World t = nuke(plane(world(false), 1, 0, "transport", FIELD), 8, "fusion_5kt", FIELD);
        t = EX.execute(t, 0, new Command.Arm(1, 8, false)).world();
        assertThat(t.nuke(8).plane()).isEqualTo(1);
        assertThat(EX.execute(t, 0, new Command.Drop(List.of(1L), FIELD, "food", List.of())).error()).contains("carries warhead #8");
    }

    @Test
    void aWarheadOnABomberGoesOffWhereItBombs() {
        World w = nuke(plane(world(true), 1, 0, "bomber", FIELD), 7, "fission_10kt", FIELD);
        w = EX.execute(w, 0, new Command.Arm(1, 7, true)).world();
        CommandResult r = EX.execute(w, 0, new Command.Bomb(1, THEIRS, false));
        assertThat(r.error()).as(r.error()).isNull();
        if (r.info().contains("dropped warhead")) {
            assertThat(r.info()).contains("an airburst of a 10kt fission");
            assertThat(r.world().nukes()).isEmpty();
            assertThat(r.world().plane(1)).as("the bomber flies home").isNotNull();
            assertThat(r.world().sector(FIELD).stock().get(COM.index("shell"))).as("no bombs with a warhead aboard").isEqualTo(500);
            assertThat(r.world().sector(THEIRS).stock().get(COM.civ)).isLessThan(400);
        }
    }

    @Test
    void storedWarheadsSurviveTheUpdate() {
        World w = nuke(world(false), 7, "fission_10kt", PLANT);
        World n = Update.run(w, CFG, 3).next();
        assertThat(n.nukes()).hasSize(1);
        assertThat(n.nextNukeId()).isEqualTo(8);
    }
}
