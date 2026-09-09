package org.hastingtx.empire.sim;

import org.hastingtx.empire.agents.scripted.ScriptedAgent;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Invariants prove the physics; probes prove the point (playbook: "a simulation's
 * conservation tests can pass while its outcome is dead").
 */
class CalibrationProbesTest {

    /** A country that issues no commands for 100 updates grows civs, banks BTUs, and does not die. */
    @Test
    void sittingStillIsBoringNotFatal() {
        GameConfig cfg = TestWorlds.teaching();
        Sim sim = new Sim(cfg);
        World start = sim.newWorld(List.of("Idle"), 5);
        Sim.Result r = sim.run(start, 100, 5, id -> null);
        Sim.Row first = r.rows().get(0), tenth = r.rows().get(9), last = r.rows().get(r.rows().size() - 1);
        assertThat(tenth.civ()).as("civilians grew while the starting food lasted").isGreaterThan(first.civ());
        assertThat(last.btu()).as("BTUs banked").isGreaterThanOrEqualTo(first.btu());
        assertThat(r.world().country(0).bankrupt()).as("no bankruptcy from doing nothing").isFalse();
        assertThat(last.civ()).as("a capital with no farms starves down, but never to zero").isGreaterThan(0);
    }

    /** Food chain: farm -> capital -> manufacturing. The plant keeps eating and keeps producing for 60 updates. */
    @Test
    void foodChainFeedsTheFactory() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 4, Map.of("civ", 500.0, "food", 1500.0, "lcm", 200.0, "iron", 2000.0));
        Coord farm = Hex.stepRaw(TestWorlds.CENTER, 0, 1), farm2 = Hex.stepRaw(TestWorlds.CENTER, 1, 1), plant = Hex.stepRaw(TestWorlds.CENTER, 3, 1);
        w = TestWorlds.own(w, cfg, farm, "agribusiness", 100, 100, Map.of("civ", 300.0, "food", 200.0), Map.of("food", 200.0));
        w = TestWorlds.own(w, cfg, farm2, "agribusiness", 100, 100, Map.of("civ", 300.0, "food", 200.0), Map.of("food", 200.0));
        w = TestWorlds.own(w, cfg, plant, "light_manufacturing", 100, 100, Map.of("civ", 300.0, "food", 50.0), Map.of("food", 150.0, "iron", 300.0, "lcm", 0.0));
        double[] th = w.sector(TestWorlds.CENTER).thresholds().clone();
        th[3] = 300; // capital keeps 300 food
        w = w.withSector(w.sector(TestWorlds.CENTER).withThresholds(th));
        int starvationEvents = 0; double lcmMade = 0; int lcmIdx = 11;
        for (int u = 0; u < 60; u++) {
            var r = Update.run(w, cfg, 100 + u);
            for (var e : r.events()) if (e.type().equals("starvation") && e.at().equals(plant)) starvationEvents++;
            lcmMade += r.next().sector(TestWorlds.CENTER).stock().get(lcmIdx) - w.sector(TestWorlds.CENTER).stock().get(lcmIdx);
            w = r.next();
        }
        assertThat(starvationEvents).as("the plant never starved").isZero();
        assertThat(w.sector(plant).stock().get(0)).as("plant still populated").isGreaterThan(200);
        assertThat(lcmMade).as("lcm reached the capital").isGreaterThan(0);
    }

    /** Subsistence: a small population with no food at all lives off the land and never starves. */
    @Test
    void peopleLiveOffTheLand() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 2, Map.of("civ", 100.0));           // capital: 100 civs, zero food, fertility 80 -> limit 240
        int starvation = 0;
        for (int u = 0; u < 20; u++) { var r = Update.run(w, cfg, 300 + u); for (var e : r.events()) if (e.type().equals("starvation")) starvation++; w = r.next(); }
        assertThat(starvation).as("nobody starves under the subsistence limit").isZero();
        assertThat(w.sector(TestWorlds.CENTER).stock().get(0)).as("and they even grow, up to the limit").isGreaterThan(100).isLessThanOrEqualTo(240.5);
    }

    /** Subsistence: a crowd beyond the limit starves down toward it, never below it. */
    @Test
    void onlyTheExcessStarves() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 2, Map.of("civ", 1000.0));
        int starvation = 0;
        for (int u = 0; u < 20; u++) { var r = Update.run(w, cfg, 400 + u); for (var e : r.events()) if (e.type().equals("starvation")) starvation++; w = r.next(); }
        double civ = w.sector(TestWorlds.CENTER).stock().get(0);
        assertThat(starvation).as("the excess starves").isGreaterThan(0);
        assertThat(civ).as("but the population settles at the subsistence limit").isGreaterThanOrEqualTo(240 - 1e-6).isLessThan(400);
    }

    /** A road order is honoured: the level climbs toward the target while lcm, cash and workers last, and never past the terrain cap. */
    @Test
    void roadOrderBuildsTheRoad() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 2, Map.of("civ", 400.0, "food", 2000.0, "lcm", 500.0));
        Coord at = Hex.stepRaw(TestWorlds.CENTER, 0, 1);
        w = TestWorlds.own(w, cfg, at, "agribusiness", 80, 100, Map.of("civ", 300.0, "food", 500.0, "lcm", 200.0, "hcm", 200.0), Map.of());   // KNOWN: 2 lcm + 2 hcm + $2 + 1 mob per point
        World control = w;                                            // same world, no road order
        w = w.withSector(w.sector(at).withRoadTarget(40));
        double lcmBefore = w.sector(at).stock().get(11);
        for (int u = 0; u < 8; u++) { w = Update.run(w, cfg, 500 + u).next(); control = Update.run(control, cfg, 500 + u).next(); }
        double level = w.sector(at).roadLevel();
        assertThat(level).as("climbed toward the target").isGreaterThan(20).isLessThanOrEqualTo(40 + 1e-9);
        assertThat(control.sector(at).roadLevel()).as("no order, no road").isZero();
        assertThat(w.sector(at).stock().get(11)).as("paid in lcm").isLessThan(lcmBefore);
        assertThat(w.country(0).cash()).as("paid in cash, relative to the control").isLessThan(control.country(0).cash());
        assertThat(cfg.infrastructure().road().maxLevelByTerrain().get("plains")).isEqualTo(100.0);
    }

    /** Issue #43: a road order above the terrain cap becomes an order for the cap, with a note, not a refusal. */
    @Test
    void roadOrderAboveTheTerrainCapIsCappedNotRefused() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 2, Map.of("civ", 300.0, "food", 1000.0));
        Coord at = Hex.stepRaw(TestWorlds.CENTER, 0, 1);
        w = TestWorlds.own(w, cfg, at, "agribusiness", 100, 100, Map.of("civ", 200.0, "food", 100.0), Map.of());
        Sector s = w.sector(at);
        w = w.withSector(s.withTerrain(org.hastingtx.empire.engine.model.Terrain.MOUNTAIN, s.elevation(), s.resources()));
        double cap = cfg.infrastructure().road().maxLevelByTerrain().get("mountain");
        assertThat(cap).isLessThan(100);
        var r = new org.hastingtx.empire.engine.command.CommandExecutor(cfg).execute(w, 0, new org.hastingtx.empire.engine.command.Command.BuildRoad(at, 100));
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.world().sector(at).roadTarget()).isEqualTo(cap);
        assertThat(r.info()).contains("mountain cap").contains("100 asked");
        var ok = new org.hastingtx.empire.engine.command.CommandExecutor(cfg).execute(w, 0, new org.hastingtx.empire.engine.command.Command.BuildRoad(at, cap));
        assertThat(ok.info()).as("an order at the cap needs no note").isNull();
    }

    /** A manual move is immediate: the goods land now, the sending sector pays mobility now, and its mobility caps the quantity. */
    @Test
    void manualMoveIsImmediate() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 2, Map.of("civ", 300.0, "food", 1000.0, "iron", 500.0));
        Coord plant = Hex.stepRaw(TestWorlds.CENTER, 0, 1);
        w = TestWorlds.own(w, cfg, plant, "light_manufacturing", 100, 40, Map.of("civ", 200.0, "food", 100.0), Map.of());
        w = w.withSector(w.sector(TestWorlds.CENTER).withMobility(40));
        var exec = new org.hastingtx.empire.engine.command.CommandExecutor(cfg);
        var r = exec.execute(w, 0, new org.hastingtx.empire.engine.command.Command.Move(TestWorlds.CENTER, plant, "iron", 400));
        assertThat(r.ok()).as(r.error()).isTrue();
        int iron = 4;
        double moved = r.world().sector(plant).stock().get(iron);
        assertThat(moved).as("iron is in the plant now, capped by the capital's 40 mobility").isGreaterThan(0).isLessThan(400);
        assertThat(r.world().sector(TestWorlds.CENTER).stock().get(iron)).isCloseTo(500 - moved, within(1e-6));
        assertThat(r.world().sector(TestWorlds.CENTER).mobility()).as("the sending sector paid nearly all of its 40 mobility").isLessThan(0.5);
        assertThat(r.world().sector(plant).mobility()).as("the entered sector paid nothing").isCloseTo(40, within(1e-9));
        assertThat(r.info()).contains("ran out");
        // and the plant produces at the very next update
        World next = Update.run(r.world(), cfg, 1).next();
        assertThat(next.sector(plant).stock().get(11)).as("lcm produced from the delivered iron").isGreaterThan(0);
    }

    /** Civilians pushed past a sector's cap are truncated at the update (KNOWN: trunc_people). */
    @Test
    void overcrowdingIsTruncated() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 2, Map.of("civ", 2500.0, "food", 9000.0));   // 2500 in a 1000-cap capital
        var r = Update.run(w, cfg, 77);
        assertThat(r.next().sector(TestWorlds.CENTER).stock().get(0)).isCloseTo(1000, within(1e-6));
        assertThat(r.events()).anyMatch(e -> e.type().equals("overcrowding"));
    }

    /** Roads rot when nobody pays for them, at the configured rate. */
    @Test
    void unpaidRoadsRot() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 2, Map.of("civ", 100.0, "food", 500.0));
        Coord at = Hex.stepRaw(TestWorlds.CENTER, 0, 1);
        w = TestWorlds.own(w, cfg, at, "agribusiness", 50, 50, Map.of("civ", 50.0, "food", 100.0), Map.of());
        w = w.withSector(w.sector(at).withRoadLevel(40));
        w = w.withCountry(w.country(0).withCash(0));
        double decay = cfg.infrastructure().road().decayPerUpdate();
        for (int u = 0; u < 10; u++) w = Update.run(w, cfg, u).next();
        // tax income is tiny; with zero treasury the upkeep is unaffordable most updates, so the level falls
        assertThat(w.sector(at).roadLevel()).isLessThan(40);
        assertThat(w.sector(at).roadLevel()).isGreaterThanOrEqualTo(40 - 10 * decay - 1e-9);
    }

    /** The scripted agent actually grows a country: more sectors, more people, no starvation spiral. */
    @Test
    void scriptedAgentExpands() {
        GameConfig cfg = TestWorlds.teaching();
        Sim sim = new Sim(cfg);
        Sim.Result r = sim.run(sim.newWorld(List.of("Bot"), 9), 40, 9, id -> new ScriptedAgent());
        Sim.Row first = r.rows().get(0), last = r.rows().get(r.rows().size() - 1);
        assertThat(last.sectors()).as("territory grew").isGreaterThan(first.sectors());
        assertThat(last.civ()).as("population grew").isGreaterThan(first.civ());
        long rejected = r.turns().stream().mapToLong(t -> t.errors().size()).sum();
        long issued = r.turns().stream().mapToLong(t -> t.commandsIssued()).sum();
        assertThat(rejected).as("most agent commands are legal (%d of %d rejected)", rejected, issued).isLessThan(issued / 2 + 1);
    }
}
