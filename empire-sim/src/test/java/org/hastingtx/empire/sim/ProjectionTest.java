package org.hastingtx.empire.sim;

import org.hastingtx.empire.agents.scripted.ScriptedAgent;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Projection;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The budget projection is the update itself, so it must match the update to the cent. */
class ProjectionTest {
    /** Issue #152: the projection names the sectors that will starve, in the player's coordinates, with a reason. */
    @Test
    void projectionNamesTheStarvingSectors() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 2, java.util.Map.of("civ", 100.0, "food", 2000.0));
        org.hastingtx.empire.engine.model.Coord rim = org.hastingtx.empire.engine.geo.Hex.stepRaw(TestWorlds.CENTER, 0, 2);
        w = TestWorlds.own(w, cfg, rim, "agribusiness", 100, 100, java.util.Map.of("civ", 800.0, "food", 0.0), java.util.Map.of());
        org.hastingtx.empire.engine.model.Sector s = w.sector(rim);
        w = w.withSector(s.withTerrain(s.terrain(), s.elevation(), new org.hastingtx.empire.engine.model.Resources(0, 0, 0, 0, 0)));
        Projection.Result p = Projection.of(w, cfg, 0, 5);
        assertThat(p.starvingSectors()).isEqualTo(p.starving().size()).isGreaterThan(0);
        Projection.Trouble t = p.starving().stream().filter(x -> x.at().equals(new org.hastingtx.empire.engine.model.Coord(2, 0))).findFirst().orElseThrow();
        assertThat(t.designation()).isEqualTo("agribusiness");
        assertThat(t.amount()).isGreaterThan(0);
        assertThat(t.hint()).as("wired to the capital but nothing set to pull food").contains("food 0").contains("800 people").contains("no food threshold");
    }

    /**
     * Issue #226: education's upkeep, which nothing showed. A school fed exactly the points the projection says hold the
     * level holds it; the points it says the next update makes are what the school made.
     */
    @Test
    void theProjectionSaysWhatEducationCostsToHold() {
        GameConfig cfg = TestWorlds.teaching();
        org.hastingtx.empire.engine.model.Coord school = org.hastingtx.empire.engine.geo.Hex.stepRaw(TestWorlds.CENTER, 1, 1);
        java.util.function.DoubleFunction<World> fed = lcm -> {
            World w = TestWorlds.disc(cfg, 2, java.util.Map.of("civ", 900.0, "food", 2000.0));
            w = w.withCountry(w.country(0).withLevels(new org.hastingtx.empire.engine.model.Levels(0, 0, 20, 0)));
            return TestWorlds.own(w, cfg, school, "school", 100, 127, java.util.Map.of("civ", 1000.0, "food", 2000.0, "lcm", lcm), java.util.Map.of());
        };
        Projection.Result idle = Projection.of(fed.apply(0), cfg, 0, 5);
        assertThat(idle.educationMade()).as("nothing to teach with").isCloseTo(0, within(1e-6));
        assertThat(idle.educationAfter()).as("so it falls").isLessThan(20);
        double hold = idle.educationToHold();
        assertThat(hold).as("a small country holds 20 cheaply, but not for free").isBetween(1.0, 600.0);

        // schools teach whole points: the next whole point above the upkeep holds it, the one below does not
        Projection.Result held = Projection.of(fed.apply(Math.ceil(hold)), cfg, 0, 5);
        assertThat(held.educationMade()).as("the school made what it was fed").isCloseTo(Math.ceil(hold), within(1e-6));
        assertThat(held.educationAfter()).as("and that holds the level").isGreaterThanOrEqualTo(20 - 1e-9).isLessThan(20.5);
        assertThat(Projection.of(fed.apply(Math.ceil(hold) - 1), cfg, 0, 5).educationAfter()).as("a point less does not").isLessThan(20);
    }

    @Test
    void projectionEqualsTheRealUpdate() {
        GameConfig cfg = TestWorlds.teaching();
        Sim sim = new Sim(cfg);
        World w = sim.run(sim.newWorld(List.of("A", "B"), 21), 6, 21, id -> new ScriptedAgent()).world();
        Projection.Result p = Projection.of(w, cfg, 0, 777);
        World next = Update.run(w, cfg, 777).next();
        assertThat(p.cashAfter()).isCloseTo(next.country(0).cash(), within(1e-9));
        assertThat(p.btuAfter()).isCloseTo(next.country(0).btu(), within(1e-9));
        assertThat(p.forUpdate()).isEqualTo(w.updateNumber() + 1);
        assertThat(p.cashNow()).isEqualTo(w.country(0).cash());
    }
}
