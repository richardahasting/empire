package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Richard, 2026-09-11: "the sea is a very demanding place. It creates a serious maintenance problem
 * for anything floating around in it." A hull afloat loses efficiency every update; at 60% it breaks
 * off whatever it is doing and goes home to be refurbished.
 */
class SeaWearTest {

    private static final GameConfig CFG = new ConfigLoader().loadPreset("sandbox").config();
    private static final long SEED = 11L;
    private static final double WEAR = CFG.units().ships().seaWearPerUpdate();

    private static World world() { return new WorldGenerator(CFG).generate(List.of("A"), SEED); }

    private static Coord openWater(World w) {
        return w.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN).map(Sector::at).findFirst().orElseThrow();
    }

    private static World withShip(World w, double efficiency, String mission, Coord at, Coord home) {
        Commodities com = Commodities.of(CFG);
        return w.withShips(List.of(new Ship(1, 0, "fishing_boat", "Nell", at, efficiency, Stocks.zero(com.size()),
                null, null, 0, null, 0, mission, home, 10000, 1000, 100)), 2);
    }

    @Test
    void theSeaWearsAHullDown() {
        World w = world();
        Coord sea = openWater(w);
        World after = Update.run(withShip(w, 100, null, sea, sea), CFG, SEED).next();
        assertThat(after.ship(1).efficiency()).isEqualTo(100 - WEAR);
    }

    @Test
    void itKeepsWearingEveryUpdate() {
        World w = withShip(world(), 100, null, openWater(world()), openWater(world()));
        for (int i = 0; i < 4; i++) w = Update.run(w, CFG, SEED).next();
        assertThat(w.ship(1).efficiency()).isEqualTo(100 - 4 * WEAR);
    }

    /** A harbour looks after its ships: no wear, and the fit-out puts the points back. */
    @Test
    void aHullInItsOwnHarbourDoesNotWear() {
        World w = world();
        Sector harbor = w.sectors().stream().filter(s -> s.owner() == 0).findFirst().orElseThrow();
        w = w.withSector(harbor.withDesignation("harbor", 100));
        World after = Update.run(withShip(w, 80, null, harbor.at(), harbor.at()), CFG, SEED).next();
        assertThat(after.ship(1).efficiency()).as("looked after, not worn").isGreaterThanOrEqualTo(80.0);
    }

    /** The rule Richard asked for: at 60% it stops working and makes for home. */
    @Test
    void aWornHullBreaksOffAndMakesForHome() {
        World w = world();
        Sector harbor = w.sectors().stream().filter(s -> s.owner() == 0).findFirst().orElseThrow();
        w = w.withSector(harbor.withDesignation("harbor", 100));
        Coord sea = openWater(w);

        World after = Update.run(withShip(w, 60, Ship.FISH, sea, harbor.at()), CFG, SEED).next();
        Ship s = after.ship(1);
        assertThat(s.note()).contains("too worn to work");
        assertThat(s.dest()).as("bound for its harbour, not for the grounds").isEqualTo(harbor.at());
        assertThat(s.mission()).as("the mission is kept, so it goes back out by itself").isEqualTo(Ship.FISH);
    }

    @Test
    void aFitHullCarriesOnWorking() {
        World w = world();
        Sector harbor = w.sectors().stream().filter(s -> s.owner() == 0).findFirst().orElseThrow();
        w = w.withSector(harbor.withDesignation("harbor", 100));
        Ship s = Update.run(withShip(w, 100, Ship.FISH, openWater(w), harbor.at()), CFG, SEED).next().ship(1);
        assertThat(s.note()).doesNotContain("too worn");
    }

    /**
     * It stays in until it is fully refitted. A harbour restores 20 points an update, so a badly worn
     * hull is in for several — and goes back out by itself the update it reaches 100, with no order.
     */
    @Test
    void itRefitsToFullThenGoesBackOutByItself() {
        World w = world();
        Sector harbor = w.sectors().stream().filter(s -> s.owner() == 0).findFirst().orElseThrow();
        w = w.withSector(harbor.withDesignation("harbor", 100));

        World cur = withShip(w, 55, Ship.FISH, harbor.at(), harbor.at());
        boolean sawRefitting = false;
        for (int update = 1; update <= 4; update++) {
            cur = Update.run(cur, CFG, SEED).next();
            Ship s = cur.ship(1);
            if (s.efficiency() < 100) {
                assertThat(s.note()).as("update %d at %s%%", update, s.efficiency()).contains("refitting");
                assertThat(s.dest()).as("still in, not sent back to the grounds").isNull();
                sawRefitting = true;
            }
        }
        assertThat(sawRefitting).as("a hull at 55%% cannot be put right in one update").isTrue();
        assertThat(cur.ship(1).efficiency()).as("and ends fully refitted").isEqualTo(100.0);
        assertThat(cur.ship(1).mission()).as("with its mission intact, so it works again unbidden").isEqualTo(Ship.FISH);
    }

    /** A hull the harbour can put right in one update does not linger. */
    @Test
    void aNearlyFitHullGoesStraightBackOut() {
        World w = world();
        Sector harbor = w.sectors().stream().filter(s -> s.owner() == 0).findFirst().orElseThrow();
        w = w.withSector(harbor.withDesignation("harbor", 100));
        Ship s = Update.run(withShip(w, 90, Ship.FISH, harbor.at(), harbor.at()), CFG, SEED).next().ship(1);
        assertThat(s.efficiency()).isEqualTo(100.0);
        assertThat(s.note()).doesNotContain("refitting");
    }

    @Test
    void wearNeverTakesAHullBelowNothing() {
        World w = withShip(world(), 1, null, openWater(world()), openWater(world()));
        w = Update.run(w, CFG, SEED).next();
        assertThat(w.ship(1).efficiency()).isBetween(0.0, 1.0);
    }
}
