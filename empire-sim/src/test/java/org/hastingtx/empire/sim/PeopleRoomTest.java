package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Issue #48: people never move into a sector that has no room for them; nobody is shipped to be truncated. */
class PeopleRoomTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int CIV = COM.civ;
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord A = Hex.stepRaw(CAP, 0, 1);

    /** The capital sits at its 1000 cap; A has 978 civilians and a civ threshold of 100 pushing surplus to the capital. */
    private static World world(double capitalCivs) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", capitalCivs, "food", 5000.0));
        return TestWorlds.own(w, CFG, A, "agribusiness", 100, 127, Map.of("civ", 978.0, "food", 2000.0), Map.of("civ", 100.0));
    }

    @Test
    void nobodyIsPushedIntoAFullCapital() {
        UpdateResult r = Update.run(world(1000), CFG, 5);
        assertThat(r.events()).noneMatch(e -> e.type().equals("overcrowding"));
        assertThat(r.next().sector(A).stock().get(CIV)).isGreaterThanOrEqualTo(978);        // they stayed (and may have bred)
        assertThat(r.next().sector(CAP).stock().get(CIV)).isCloseTo(1000, within(1e-6));
        assertThat(r.flows()).filteredOn(f -> f.commodity() == CIV).allMatch(f -> f.qtyMoved() <= 1e-9);
    }

    @Test
    void onlyAsManyAsFitAreShipped() {
        // an empty capital (nobody to breed) with room for 1000; two sectors each push 878 — 1756 wanted, exactly 1000 arrive
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 0.0, "food", 5000.0));
        Coord b = Hex.stepRaw(CAP, 3, 1);
        for (Coord at : new Coord[] {A, b}) w = TestWorlds.own(w, CFG, at, "agribusiness", 100, 127, Map.of("civ", 978.0, "food", 2000.0), Map.of("civ", 100.0));
        UpdateResult r = Update.run(w, CFG, 5);
        assertThat(r.events()).noneMatch(e -> e.type().equals("overcrowding"));
        assertThat(r.next().sector(CAP).stock().get(CIV)).isCloseTo(1000, within(1e-6));
        double moved = r.flows().stream().filter(f -> f.commodity() == CIV && f.completed()).mapToDouble(f -> f.qtyMoved()).sum();
        assertThat(moved).isCloseTo(1000, within(1e-6));
        assertThat(r.next().sector(A).stock().get(CIV) + r.next().sector(b).stock().get(CIV)).isGreaterThanOrEqualTo(2 * 978 - 1000);
    }

    @Test
    void handMovesOfPeopleAreCappedByRoomToo() {
        World w = world(1000);
        CommandExecutor ex = new CommandExecutor(CFG);
        CommandResult full = ex.execute(w, 0, new Command.Move(A, CAP, "civ", 100));
        assertThat(full.error()).contains("is full");
        World w2 = world(990);
        CommandResult some = ex.execute(w2, 0, new Command.Move(A, CAP, "civ", 100));
        assertThat(some.error()).isNull();
        assertThat(some.world().sector(CAP).stock().get(CIV)).isCloseTo(1000, within(1e-6));
        assertThat(some.info()).contains("room for no more");
    }
}
