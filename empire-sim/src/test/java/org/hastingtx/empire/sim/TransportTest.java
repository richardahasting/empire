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

/** Issue #71: air transport — KNOWN commands/fly.c, drop.c, para.c, plnsub.c pln_equip and pln_dropoff. */
class TransportTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord FIELD = Hex.stepRaw(CAP, 0, 2);        // our airfield on the rim
    private static final Coord FIELD2 = Hex.stepRaw(CAP, 3, 2);       // another of ours, across the disc
    private static final Coord THEIRS = Hex.stepRaw(CAP, 0, 3);       // their farm next door
    private static final Coord THEIR_FIELD = Hex.stepRaw(CAP, 0, 4);
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int PET = COM.index("pet"), FOOD = COM.food, GUN = COM.index("gun");

    private static Sector land(World w, Coord at, int owner, String des, Map<String, Double> stock) {
        return w.sector(at).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)).withOwner(owner).withDesignation(des, 100)
                .withMobility(100).withStock(Stocks.of(COM.fromMap(stock)));
    }

    private static World world(boolean war, double theirMil) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = w.withCountry(w.country(0).withCash(100000).withLevels(new Levels(200, 0, 0, 0)));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", THEIR_FIELD, 100000, 640, new Levels(200, 0, 0, 0), HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = TestWorlds.own(w, CFG, FIELD, "airfield", 100, 127, Map.of("civ", 300.0, "mil", 200.0, "food", 1000.0, "pet", 100.0, "gun", 100.0), Map.of());
        w = TestWorlds.own(w, CFG, FIELD2, "airfield", 100, 127, Map.of("civ", 300.0, "food", 100.0), Map.of());
        w = w.withSector(land(w, THEIRS, 1, "agribusiness", Map.of("civ", 400.0, "food", 900.0, "mil", theirMil)));
        w = w.withSector(land(w, THEIR_FIELD, 1, "airfield", Map.of("civ", 300.0, "food", 500.0, "pet", 100.0)));
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        return w;
    }

    private static World plane(World w, long id, int owner, String cls, Coord at) { return w.withPlane(new Plane(id, owner, cls, at, 100, 200, 0, "")); }

    private static World ok(World w, int who, Command c) {
        CommandResult r = EX.execute(w, who, c);
        assertThat(r.error()).as(r.error()).isNull();
        return r.world();
    }

    private static double carries(String cls, double times, int ci) {
        return Math.floor(CFG.units().planes().planeClass(cls).loadAt(200) * times / CFG.commodities().get(ci).weight());
    }

    @Test
    void aTransportFliesItsLoadTwiceOverToAnotherFieldAndStays() {
        World w = plane(plane(world(false, 0), 1, 0, "transport", FIELD), 2, 0, "transport", FIELD);
        World r = ok(w, 0, new Command.Fly(List.of(1L, 2L), FIELD2, "food", List.of()));
        double each = carries("transport", 2, FOOD);
        assertThat(each).isGreaterThan(0);
        assertThat(r.sector(FIELD2).stock().get(FOOD)).isEqualTo(100 + 2 * each);
        assertThat(r.sector(FIELD).stock().get(FOOD)).isEqualTo(1000 - 2 * each);
        assertThat(r.plane(1).at()).isEqualTo(FIELD2);
        assertThat(r.plane(2).at()).isEqualTo(FIELD2);
        assertThat(r.sector(FIELD).stock().get(PET)).as("each sortie's petrol").isEqualTo(100 - 2 * CFG.units().planes().planeClass("transport").fuel());
        // guns weigh ten times as much
        World guns = ok(w, 0, new Command.Fly(List.of(1L), FIELD2, "gun", List.of()));
        assertThat(guns.sector(FIELD2).stock().get(GUN)).isEqualTo(carries("transport", 2, GUN));
    }

    @Test
    void anyPlaneMayFerryButOnlyATransportCarries() {
        World w = plane(world(false, 0), 1, 0, "bomber", FIELD);
        assertThat(ok(w, 0, new Command.Fly(List.of(1L), FIELD2, null, List.of())).plane(1).at()).isEqualTo(FIELD2);
        assertThat(EX.execute(w, 0, new Command.Fly(List.of(1L), FIELD2, "food", List.of())).error()).contains("none of them is a cargo plane");
    }

    @Test
    void whereAPlaneMayFly() {
        World w = plane(plane(world(false, 0), 1, 0, "transport", FIELD), 2, 0, "transport", FIELD2);
        assertThat(EX.execute(w, 0, new Command.Fly(List.of(1L), CAP, null, List.of())).error()).contains("not an airfield of yours");
        assertThat(EX.execute(w, 0, new Command.Fly(List.of(1L), THEIR_FIELD, null, List.of())).error()).contains("not an airfield of yours");
        assertThat(EX.execute(w, 0, new Command.Fly(List.of(1L, 2L), FIELD2, null, List.of())).error()).contains("a sortie flies from one field");
        World run = w.withSector(w.sector(FIELD2).withDesignation("airfield", 50));
        assertThat(EX.execute(run, 0, new Command.Fly(List.of(1L), FIELD2, null, List.of())).error()).contains("land at 60");
    }

    @Test
    void aDropFeedsASectorOfYoursAndTheyFlyHome() {
        World w = plane(world(false, 0), 1, 0, "transport", FIELD);
        World r = ok(w, 0, new Command.Drop(List.of(1L), CAP, "food", List.of()));
        assertThat(r.sector(CAP).stock().get(FOOD)).isEqualTo(5000 + carries("transport", 1, FOOD));
        assertThat(r.plane(1).at()).as("home again").isEqualTo(FIELD);
        assertThat(EX.execute(w, 0, new Command.Drop(List.of(1L), THEIRS, "food", List.of())).error()).contains("land of yours");
        assertThat(EX.execute(plane(world(false, 0), 1, 0, "bomber", FIELD), 0, new Command.Drop(List.of(1L), CAP, "food", List.of())).error()).contains("cargo plane");
    }

    @Test
    void paratroopsTakeAnUndefendedSectorAndDieAgainstAStrongOne() {
        World w = plane(plane(world(true, 0), 1, 0, "transport", FIELD), 2, 0, "transport", FIELD);
        World r = ok(w, 0, new Command.Paradrop(List.of(1L, 2L), THEIRS, List.of()));
        double sticks = 2 * carries("transport", 1, COM.mil);
        assertThat(r.sector(THEIRS).owner()).as("nobody there to stop them").isZero();
        assertThat(r.sector(THEIRS).stock().get(COM.mil)).isEqualTo(sticks);
        assertThat(r.sector(FIELD).stock().get(COM.mil)).isEqualTo(200 - sticks);
        assertThat(r.plane(1).at()).isEqualTo(FIELD);

        World held = plane(world(true, 5000), 1, 0, "transport", FIELD);
        CommandResult lost = EX.execute(held, 0, new Command.Paradrop(List.of(1L), THEIRS, List.of()));
        assertThat(lost.info()).contains("all of them lost");
        assertThat(lost.world().sector(THEIRS).owner()).isEqualTo(1);
    }

    /** KNOWN takeover.c takeover_plane: the loser's planes on a taken sector lose 29 + roll(100); captured, or blown up by their crews. */
    @Test
    void theirPlanesOnATakenSectorAreCapturedOrBlownUp() {
        World w = plane(plane(world(true, 0), 1, 0, "transport", FIELD), 2, 0, "transport", FIELD);
        w = w.withPlane(new Plane(9, 1, "fighter_2", THEIRS, 100, 200, 0, ""));
        CommandResult r = EX.execute(w, 0, new Command.Paradrop(List.of(1L, 2L), THEIRS, List.of()));
        assertThat(r.world().sector(THEIRS).owner()).isZero();
        Plane p = r.world().plane(9);
        assertThat(p == null || (p.owner() == 0 && p.efficiency() <= 100 - 30)).as(r.info()).isTrue();
        assertThat(r.info()).containsAnyOf("captured their plane #9", "their plane #9 blown up by its crew");
    }

    @Test
    void whereParatroopsCannotGo() {
        World w = plane(world(false, 0), 1, 0, "transport", FIELD);
        assertThat(EX.execute(w, 0, new Command.Paradrop(List.of(1L), THEIRS, List.of())).error()).as("at peace").contains("only at war");
        World war = plane(world(true, 0), 1, 0, "transport", FIELD);
        assertThat(EX.execute(war, 0, new Command.Paradrop(List.of(1L), CAP, List.of())).error()).contains("is yours");
        World cap = war.withSector(war.sector(THEIRS).withDesignation("capital", 100));
        assertThat(EX.execute(cap, 0, new Command.Paradrop(List.of(1L), THEIRS, List.of())).error()).contains("cannot take a capital");
        World hill = war.withSector(war.sector(THEIRS).withTerrain(Terrain.MOUNTAIN, 500, new Resources(0, 50, 0, 0, 0)));
        assertThat(EX.execute(hill, 0, new Command.Paradrop(List.of(1L), THEIRS, List.of())).error()).contains("cannot land on mountain");
        assertThat(EX.execute(plane(war, 2, 0, "fighter_2", FIELD), 0, new Command.Paradrop(List.of(2L), THEIRS, List.of())).error()).contains("a fighter cannot paradrop");
    }

    @Test
    void whatAShotDownTransportCarriedIsLost() {
        World w = plane(world(true, 0), 1, 0, "transport", FIELD);
        for (long id = 2; id <= 4; id++) w = plane(w, id, 1, "jet_fighter_2", THEIR_FIELD);
        w = w.withPlane(w.plane(2).withMission(Plane.AIR_DEFENCE, CAP, 4)).withPlane(w.plane(3).withMission(Plane.AIR_DEFENCE, CAP, 4)).withPlane(w.plane(4).withMission(Plane.AIR_DEFENCE, CAP, 4));
        CommandResult r = EX.execute(w, 0, new Command.Drop(List.of(1L), CAP, "food", List.of()));
        assertThat(r.info()).contains("rose");
        double dropped = r.world().sector(CAP).stock().get(FOOD) - 5000;
        assertThat(r.world().sector(FIELD).stock().get(FOOD)).as("it left the field when the plane took off").isEqualTo(1000 - carries("transport", 1, FOOD));
        if (r.world().plane(1) == null || r.info().contains("turned back")) assertThat(dropped).isZero();
    }

    @Test
    void aPlaneCannotBeBothInTheSortieAndItsEscort() {
        World w = plane(plane(world(false, 0), 1, 0, "fighter_2", FIELD), 2, 0, "fighter_2", FIELD);
        assertThat(EX.execute(w, 0, new Command.Fly(List.of(1L, 2L), FIELD2, null, List.of(2L))).error()).contains("named twice");
    }

    @Test
    void noMoreIsCarriedThanTheFieldCanTake() {
        World w = plane(world(false, 0), 1, 0, "jet_transport", FIELD);
        double cap = CFG.economy().defaultCapacity();
        World full = w.withSector(w.sector(FIELD2).withStock(w.sector(FIELD2).stock().with(FOOD, cap - 3)));
        World r = ok(full, 0, new Command.Fly(List.of(1L), FIELD2, "food", List.of()));
        assertThat(r.sector(FIELD2).stock().get(FOOD)).as("three more, and nothing for the update to cut away").isLessThanOrEqualTo(cap);
        assertThat(r.sector(FIELD).stock().get(FOOD)).isEqualTo(1000 - 3);
    }

    @Test
    void civiliansFlyOnlyBetweenTheirOwnPeoplesLand() {
        World w = plane(world(false, 0), 1, 0, "transport", FIELD);
        World occupied = w.withSector(w.sector(FIELD).withUnrest(0, 100, 1, 0, Sector.NOBODY));
        assertThat(EX.execute(occupied, 0, new Command.Fly(List.of(1L), FIELD2, "civ", List.of())).error()).contains("conquered people");
        assertThat(ok(w, 0, new Command.Fly(List.of(1L), FIELD2, "civ", List.of())).sector(FIELD2).stock().get(COM.civ)).isGreaterThan(300);
    }
}
