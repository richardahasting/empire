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
 * Issue #71, slice 3b: fighters, interception and escorts — KNOWN aircombat.c {@code ac_encounter}, {@code ac_intercept},
 * {@code ac_airtoair}, {@code ac_dog}; plnsub.c {@code pln_sel}; plane.config's fighter rows.
 */
class FighterTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord FIELD = Hex.stepRaw(CAP, 0, 2);         // our airfield on the rim
    private static final Coord THEIRS = Hex.stepRaw(CAP, 0, 3);        // their farm next door: the target
    private static final Coord THEIR_FIELD = Hex.stepRaw(CAP, 0, 4);   // their airfield, one hex beyond it
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int PET = COM.index("pet");

    private static Sector land(World w, Coord at, int owner, String des, Map<String, Double> stock) {
        return w.sector(at).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)).withOwner(owner).withDesignation(des, 100)
                .withMobility(100).withStock(Stocks.of(COM.fromMap(stock)));
    }

    private static World world(boolean war) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = w.withCountry(w.country(0).withCash(100000).withLevels(new Levels(200, 0, 0, 0)));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", THEIRS, 100000, 640, new Levels(200, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = TestWorlds.own(w, CFG, FIELD, "airfield", 100, 127, Map.of("civ", 300.0, "food", 500.0, "pet", 100.0, "shell", 100.0), Map.of());
        w = w.withSector(land(w, THEIRS, 1, "agribusiness", Map.of("civ", 400.0, "food", 900.0)));
        w = w.withSector(land(w, THEIR_FIELD, 1, "airfield", Map.of("civ", 300.0, "food", 500.0, "pet", 100.0)));
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        return w;
    }

    private static World plane(World w, long id, int owner, String cls, Coord at, double tech) {
        return w.withPlane(new Plane(id, owner, cls, at, 100, tech, 0, ""));
    }

    @Test
    void theirFightersRiseAgainstARaidAtWar() {
        World w = plane(plane(world(true), 1, 0, "bomber", FIELD, 200), 2, 1, "fighter_2", THEIR_FIELD, 200);
        CommandResult r = EX.execute(w, 0, new Command.Bomb(1, THEIRS, false));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).contains("1 fighter of Them rose over " + THEIRS).contains("#1 against their #2");
        Plane f = r.world().plane(2);
        if (f != null) assertThat(f.note()).isEqualTo("rose against a raid by " + w.country(0).name());
        double sortie = CFG.units().planes().planeClass("fighter_2").fuel();
        assertThat(r.world().sector(THEIR_FIELD).stock().get(PET)).as("the fighter's petrol came off its field").isEqualTo(100 - sortie);
    }

    @Test
    void atPeaceNobodyRises() {
        World w = plane(plane(world(false), 1, 0, "recon", FIELD, 200), 2, 1, "fighter_2", THEIR_FIELD, 200);
        CommandResult r = EX.execute(w, 0, new Command.Recon(1, THEIRS));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).doesNotContain("rose");
        assertThat(r.world().plane(2).note()).isEmpty();
    }

    @Test
    void anEscortOnlyPlaneDoesNotRise() {
        World w = plane(plane(world(true), 1, 0, "bomber", FIELD, 200), 2, 1, "escort", THEIR_FIELD, 200);
        assertThat(EX.execute(w, 0, new Command.Bomb(1, THEIRS, false)).info()).doesNotContain("rose");
    }

    @Test
    void aFighterOutOfRangeStaysOnTheGround() {
        // a biplane flies 4 there and back at its class's tech: it can meet a raid one hex off, not three
        Coord far = Hex.stepRaw(CAP, 0, 6);
        World w = world(true).withSector(land(world(true), far, 1, "airfield", Map.of("civ", 100.0, "food", 100.0, "pet", 100.0)));
        w = plane(plane(w, 1, 0, "bomber", FIELD, 200), 2, 1, "fighter_1", far, 50);
        assertThat(EX.execute(w, 0, new Command.Bomb(1, THEIRS, false)).info()).doesNotContain("rose");
        w = plane(w.withoutPlane(2), 2, 1, "fighter_1", THEIR_FIELD, 50);
        assertThat(EX.execute(w, 0, new Command.Bomb(1, THEIRS, false)).info()).contains("rose");
    }

    @Test
    void escortsFightTheInterceptorsFirst() {
        World w = plane(plane(plane(world(true), 1, 0, "bomber", FIELD, 200), 3, 0, "jet_fighter_2", FIELD, 300), 2, 1, "fighter_1", THEIR_FIELD, 50);
        CommandResult r = EX.execute(w, 0, new Command.Bomb(1, THEIRS, false, List.of(3L)));
        assertThat(r.error()).as(r.error()).isNull();
        String story = r.info();
        assertThat(story.indexOf("#3 against their #2")).as("the escort meets them first: " + story).isGreaterThanOrEqualTo(0);
        int bomberFought = story.indexOf("#1 against their #2");
        assertThat(bomberFought < 0 || bomberFought > story.indexOf("#3 against their #2")).isTrue();
        double escortFuel = CFG.units().planes().planeClass("jet_fighter_2").fuel(), bomberFuel = CFG.units().planes().planeClass("bomber").fuel();
        assertThat(r.world().sector(FIELD).stock().get(PET)).as("both sorties' petrol off our field").isEqualTo(100 - bomberFuel - escortFuel);
    }

    @Test
    void aStrongInterceptionStopsALoneBomber() {
        World w = plane(world(true), 1, 0, "bomber", FIELD, 90);
        for (long id = 2; id <= 4; id++) w = plane(w, id, 1, "jet_fighter_2", THEIR_FIELD, 300);
        CommandResult r = EX.execute(w, 0, new Command.Bomb(1, THEIRS, false));
        assertThat(r.info()).as("a raid of one meets two of them: one each and one more").contains("2 fighters of Them rose");
        Plane b = r.world().plane(1);
        assertThat(b == null || b.efficiency() < 50).as("shot down or badly hurt: " + r.info()).isTrue();
    }

    /** KNOWN aircombat.c only_mission, miss.c: on air defence a fighter rises over anyone's sector in its area, not only its own. */
    @Test
    void onAirDefenceAFighterRisesOverAnyonesSector() {
        Coord ours = Hex.stepRaw(CAP, 0, 1);   // our land: a recon over it meets nobody's fighters, unless they guard it
        World w = plane(plane(world(true), 1, 0, "recon", FIELD, 200), 2, 1, "fighter_2", THEIR_FIELD, 200);
        assertThat(EX.execute(w, 0, new Command.Recon(1, ours)).info()).as("not their land, no mission: it stays down").doesNotContain("rose");
        World guarding = run(w, 1, new Command.AirMission(2, ours, 1, false));
        assertThat(guarding.plane(2).onAirDefence()).isTrue();
        assertThat(EX.execute(guarding, 0, new Command.Recon(1, ours)).info()).contains("1 fighter of Them rose over");
    }

    @Test
    void whatAirDefenceTakes() {
        World w = plane(plane(plane(world(true), 1, 0, "bomber", FIELD, 200), 2, 1, "fighter_2", THEIR_FIELD, 200), 3, 1, "escort", THEIR_FIELD, 200);
        assertThat(EX.execute(w, 1, new Command.AirMission(3, THEIRS, 1, false)).error()).contains("only fighters fly air defence");
        assertThat(EX.execute(w, 1, new Command.AirMission(1, THEIRS, 1, false)).error()).contains("no plane #1 of yours");
        assertThat(EX.execute(w, 1, new Command.AirMission(2, new Coord(2, 2), 1, false)).error()).contains("it guards within");
        World r = run(w, 1, new Command.AirMission(2, THEIRS, 99, false));
        int reach = (int) Math.floor(CFG.units().planes().planeClass("fighter_2").reachAt(200));
        assertThat(r.plane(2).radius()).as("no further than it reaches").isEqualTo(reach);
        assertThat(run(r, 1, new Command.AirMission(2, null, 0, true)).plane(2).onAirDefence()).isFalse();
        assertThat(EX.execute(w, 1, new Command.AirMission(2, null, 0, true)).error()).contains("on no mission");
    }

    private static World run(World w, int who, Command c) {
        CommandResult r = EX.execute(w, who, c);
        assertThat(r.error()).as(r.error()).isNull();
        return r.world();
    }

    @Test
    void aBadEscortRefusesTheSortieBeforeAnythingFlies() {
        World w = plane(plane(world(true), 1, 0, "bomber", FIELD, 200), 3, 0, "recon", FIELD, 200);
        CommandResult notAFighter = EX.execute(w, 0, new Command.Bomb(1, THEIRS, false, List.of(3L)));
        assertThat(notAFighter.error()).contains("cannot fly escort");
        assertThat(notAFighter.world()).isSameAs(w);
        assertThat(EX.execute(w, 0, new Command.Bomb(1, THEIRS, false, List.of(9L))).error()).contains("no plane #9 of yours");
        assertThat(EX.execute(w, 0, new Command.Bomb(1, THEIRS, false, List.of(1L))).error()).contains("named twice");
        Coord five = Hex.stepRaw(CAP, 3, 3);   // five hexes from our field
        World far = TestWorlds.own(w, CFG, five, "airfield", 100, 127, Map.of("civ", 100.0, "pet", 100.0), Map.of());
        far = plane(far, 4, 0, "jet_escort", five, 200);
        assertThat(EX.execute(far, 0, new Command.Bomb(1, THEIRS, false, List.of(4L))).error()).contains("escorts fly from within 4");
    }
}
