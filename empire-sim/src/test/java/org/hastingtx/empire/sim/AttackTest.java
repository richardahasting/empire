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
 * Issue #236 (#71 slice 1). The original's most basic act of war, `attack` with military from the sectors next to an
 * enemy one, fought by Richard's assault rules (#206): at war only, man for man, the defender's neighbours fight too,
 * the winner takes the sector with its people and most of its stock.
 */
class AttackTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord FRONT = Hex.stepRaw(CAP, 0, 2);       // ours, on the rim
    private static final Coord FLANK = new Coord(14, 10);            // ours too, also next to TARGET
    private static final Coord TARGET = Hex.stepRaw(CAP, 0, 3);      // theirs, next to FRONT
    private static final Coord DEPTH = Hex.stepRaw(CAP, 0, 4);       // theirs, behind it
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    private static World world(boolean war, double targetMil, double depthMil, double frontMil) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", DEPTH, 100000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        for (Coord at : new Coord[] {TARGET, DEPTH}) w = w.withSector(w.sector(at).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)));
        w = w.withSector(w.sector(TARGET).withOwner(1).withDesignation("agribusiness", 100)
                .withStock(Stocks.of(COM.fromMap(Map.of("civ", 300.0, "mil", targetMil, "food", 500.0)))).withDistCenter(DEPTH).withRoadLevel(80));
        w = w.withSector(w.sector(DEPTH).withOwner(1).withDesignation("agribusiness", 100).withStock(Stocks.of(COM.fromMap(Map.of("civ", 300.0, "mil", depthMil)))));
        w = TestWorlds.own(w, CFG, FRONT, "agribusiness", 100, 100, Map.of("civ", 300.0, "food", 500.0, "mil", frontMil), Map.of());
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        return w;
    }

    private static Command.Attack attack(double men) { return new Command.Attack(TARGET, List.of(new Command.Attack.Party(FRONT, men))); }

    @Test
    void aWeakGarrisonFallsAndTheSurvivorsMoveIn() {
        CommandResult r = EX.execute(world(true, 10, 0, 200), 0, attack(100));
        assertThat(r.error()).as(r.error()).isNull();
        Sector s = r.world().sector(TARGET);
        assertThat(s.owner()).as("taken").isEqualTo(0);
        assertThat(s.designation()).isEqualTo("agribusiness");
        assertThat(s.stock().get(COM.mil)).as("the survivors garrison it").isBetween(1.0, 100.0);
        assertThat(s.stock().get(COM.civ)).as("its people stay").isEqualTo(300);
        assertThat(s.stock().get(COM.food)).as("a tenth of the goods lost").isEqualTo(450);
        assertThat(s.distCenter()).isNull();
        Sector home = r.world().sector(FRONT);
        assertThat(home.stock().get(COM.mil)).as("the hundred left home").isEqualTo(100);
        // KNOWN (attsub.c): the move cost of a hundred soldiers into the target, and up to 20 more for its dead
        World before = world(true, 10, 0, 200);
        double move = 100 * new org.hastingtx.empire.engine.update.Ctx(before, CFG, COM, 0).moveCostInto(before.sector(TARGET));
        assertThat(move).as("a real cost").isGreaterThan(1);
        assertThat(home.mobility()).as("and the sector paid the move, and a little for its dead")
                .isLessThanOrEqualTo(Math.round(100 - move)).isGreaterThanOrEqualTo(Math.round(100 - move) - CFG.capture().attackOrDefault().casualtyMobilityCapOr0() - 1);
        assertThat(s.mobility()).as("a taken sector's mobility is 0").isZero();
        assertThat(r.info()).contains("taken");
        assertThat(Update.run(r.world(), CFG, 7).next().sector(TARGET).owner()).as("the books balance through an update").isEqualTo(0);
    }

    @Test
    void aStrongGarrisonBeatsThemOffAndNeighboursFight() {
        CommandResult alone = EX.execute(world(true, 40, 0, 200), 0, attack(100));
        assertThat(alone.world().sector(TARGET).owner()).isEqualTo(0);
        CommandResult backed = EX.execute(world(true, 40, 300, 200), 0, attack(100));
        assertThat(backed.world().sector(TARGET).owner()).as("held in depth").isEqualTo(1);
        assertThat(backed.world().sector(FRONT).stock().get(COM.mil)).as("all hundred sent are gone; the rest stayed home").isEqualTo(100);
        assertThat(backed.world().sector(DEPTH).stock().get(COM.mil)).as("the neighbours bled").isLessThan(300);
        assertThat(backed.info()).contains("beaten off").contains("from next door");
    }

    @Test
    void severalSectorsAttackTogether() {
        World w = world(true, 60, 0, 60);
        w = w.withSector(w.sector(FLANK).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)));
        w = TestWorlds.own(w, CFG, FLANK, "agribusiness", 100, 100, Map.of("civ", 300.0, "food", 500.0, "mil", 60.0), Map.of());
        assertThat(Hex.neighbours(w, TARGET)).contains(FLANK);
        CommandResult r = EX.execute(w, 0, new Command.Attack(TARGET, List.of(new Command.Attack.Party(FRONT, 60), new Command.Attack.Party(FLANK, 60))));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.info()).startsWith("attack on " + TARGET + ": 120 mil against 60 defenders");
        assertThat(r.world().sector(FRONT).stock().get(COM.mil) + r.world().sector(FLANK).stock().get(COM.mil)).isZero();
    }

    @Test
    void theRefusals() {
        assertThat(EX.execute(world(false, 10, 0, 200), 0, attack(100)).error()).contains("only at war");
        assertThat(EX.execute(world(true, 10, 0, 50), 0, attack(100)).error()).contains("has 50 military");
        World tired = world(true, 10, 0, 200);
        tired = tired.withSector(tired.sector(FRONT).withMobility(5));
        assertThat(EX.execute(tired, 0, attack(100)).error()).as("mobility caps how many a sector can send").contains("mobility, which can carry").contains("not 100");
        assertThat(EX.execute(world(true, 10, 0, 200), 0, new Command.Attack(DEPTH, List.of(new Command.Attack.Party(FRONT, 10)))).error()).contains("not next to");
        assertThat(EX.execute(world(true, 10, 0, 200), 0, new Command.Attack(TARGET, List.of())).error()).contains("with whom");
        assertThat(EX.execute(world(true, 10, 0, 200), 0, new Command.Attack(FRONT, List.of(new Command.Attack.Party(FRONT, 10)))).error()).contains("already yours");
    }

    @Test
    void theSameAttackGoesTheSameWay() {
        World w = world(true, 70, 30, 200);
        assertThat(EX.execute(w, 0, attack(100)).info()).isEqualTo(EX.execute(w, 0, attack(100)).info());
    }
}
