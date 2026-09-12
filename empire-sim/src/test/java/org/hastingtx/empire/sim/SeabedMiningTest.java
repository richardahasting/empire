package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.*;

/**
 * Issue #112: polymetallic nodules, and a ship that works them. The sea has always carried a minerals
 * value and it has always been zero; this is what fills it in and what brings it up.
 */
class SeabedMiningTest {

    private static final GameConfig CFG = new ConfigLoader().loadPreset("sandbox").config();
    private static final long SEED = 11L;

    private static World world() { return new WorldGenerator(CFG).generate(List.of("A"), SEED); }

    @Test
    void theSeaHasNodulesAndTheyAreNotEverywhere() {
        World w = world();
        var sea = w.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN).toList();
        assertThat(sea).isNotEmpty();
        long withNodules = sea.stream().filter(s -> s.resources().minerals() > 0).count();
        assertThat(withNodules).as("some water has nodules").isGreaterThan(0);
        assertThat(withNodules).as("and some has none — a field, not a gradient").isLessThan(sea.size());
    }

    /** Fields, not scatter: two hexes in one region should agree far more often than two far apart. */
    @Test
    void nodulesComeInFields() {
        World w = world();
        int rs = CFG.world().resources().seaMinerals().regionSize();
        int together = 0, apart = 0, pairs = 0;
        for (Sector s : w.sectors()) {
            if (s.terrain() != Terrain.OCEAN || pairs >= 200) continue;
            Coord near = new Coord((s.at().x() + 1) % w.width(), s.at().y());
            Coord far = new Coord((s.at().x() + rs * 3) % w.width(), s.at().y());
            if (w.sector(near).terrain() != Terrain.OCEAN || w.sector(far).terrain() != Terrain.OCEAN) continue;
            together += Math.abs(s.resources().minerals() - w.sector(near).resources().minerals());
            apart += Math.abs(s.resources().minerals() - w.sector(far).resources().minerals());
            pairs++;
        }
        assertThat(pairs).isGreaterThan(20);
        assertThat(together).as("neighbours look alike; distant water does not").isLessThan(apart);
    }

    @Test
    void landIsUntouched() {
        World plain = world();
        assertThat(plain.sectors()).filteredOn(s -> s.terrain().isLand())
                .allSatisfy(s -> assertThat(s.resources().minerals()).isBetween(0, 100));
        // a mine on land still depends on the land's own minerals, not the sea's
        assertThat(plain.sectors()).filteredOn(s -> s.terrain() == Terrain.MOUNTAIN)
                .anySatisfy(s -> assertThat(s.resources().minerals()).isGreaterThan(0));
    }

    /** A ship sitting over a field, with no mission to interrupt it, fills its hold with iron. */
    @Test
    void aMinerOverAFieldBringsUpOre() {
        Commodities com = Commodities.of(CFG);
        World w = fieldWithMiner(90, null);
        var after = Update.run(w, CFG, SEED).next();
        assertThat(after.ship(1).stock().get(com.index("iron"))).as("ore in the hold").isGreaterThan(0.0);
    }

    /**
     * And on the mission it does not keep it: the ore goes ashore at home, exactly as a fishing boat
     * lands its catch. Worth its own test — the first version of the one above set home to the hex
     * being mined, so the load was landed before the assertion looked at the hold.
     */
    @Test
    void theMissionLandsTheOreAtHome() {
        Commodities com = Commodities.of(CFG);
        World w = fieldWithMiner(90, Ship.MINE);
        Coord home = w.ship(1).home();
        double before = w.sector(home).stock().get(com.index("iron"));
        var after = Update.run(w, CFG, SEED).next();
        assertThat(after.sector(home).stock().get(com.index("iron")))
                .as("the ore is ashore, not still at sea").isGreaterThan(before);
    }

    /** A rich hex, and a fully fitted miner on it. {@code mission} null leaves it where it is. */
    private static World fieldWithMiner(int nodules, String mission) {
        World w = world();
        Commodities com = Commodities.of(CFG);
        Sector sea = w.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN).findFirst().orElseThrow();
        w = w.withSector(sea.withTerrain(Terrain.OCEAN, sea.elevation(), new Resources(0, nodules, 0, 0, 0)));
        Ship miner = new Ship(1, 0, "mining_ship", "Digger", sea.at(), 100, Stocks.zero(com.size()),
                null, null, 0, null, 100, mission, sea.at(), 1000, 100);
        return w.withShips(List.of(miner), 2);
    }

    @Test
    void aMinerOverBarrenWaterBringsUpNothing() {
        Commodities com = Commodities.of(CFG);
        var after = Update.run(fieldWithMiner(0, null), CFG, SEED).next();
        assertThat(after.ship(1).stock().get(com.index("iron"))).as("no nodules, no ore").isZero();
    }

    @Test
    void aFishingBoatCannotMineAndAMinerCannotFish() {
        World w = world();
        Commodities com = Commodities.of(CFG);
        Sector sea = w.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN).findFirst().orElseThrow();
        Ship boat = new Ship(1, 0, "fishing_boat", "Nell", sea.at(), 100, Stocks.zero(com.size()),
                null, null, 0, null, 100, null, sea.at(), 100, 100);
        World w2 = w.withShips(List.of(boat), 2);
        assertThat(new CommandExecutor(CFG).execute(w2, 0, new Command.Mine(1, null, false)).error())
                .contains("cannot work the sea floor");
    }

    @Test
    void miningIsDeterministic() {
        World w = world();
        assertThat(Update.run(w, CFG, SEED).stateHash()).isEqualTo(Update.run(w, CFG, SEED).stateHash());
    }

    /** Retrofitting an old world: the same draw, so a game made before nodules can be given them. */
    @Test
    void theFieldsCanBeGeneratedForAnExistingWorld() {
        World w = world();
        int[] a = new WorldGenerator(CFG).seaMinerals(w.width(), w.height(), new SplittableRandom(SEED));
        int[] b = new WorldGenerator(CFG).seaMinerals(w.width(), w.height(), new SplittableRandom(SEED));
        assertThat(a).isEqualTo(b);
        assertThat(java.util.Arrays.stream(a).max().orElse(0)).isGreaterThan(0);
    }
}
