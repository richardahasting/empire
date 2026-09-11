package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hand move carries what fits, not what mobility could lift (issue #103).
 *
 * <p>Richard's case exactly: a warehouse with 4,500 iron, a works with 400 and a ceiling of 1,000,
 * and mobility enough to carry 1,900. Moving 1,900 put 2,300 in a sector that holds 1,000 and the
 * update destroyed 1,300 of it — mobility spent to deliver iron to the bin.
 */
class MoveRespectsCapacityTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int IRON = COM.index("iron");
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord SHED = Hex.stepRaw(CAP, 0, 1);
    private static final Coord WORKS = Hex.stepRaw(CAP, 0, 2);

    private static World world() {
        World w = TestWorlds.disc(CFG, 4, Map.of("civ", 500.0, "food", 500.0));
        w = w.withCountry(w.country(0).withCash(200000));
        w = TestWorlds.own(w, CFG, SHED, "warehouse", 100, 127, Map.of("civ", 400.0, "food", 400.0, "iron", 4500.0), Map.of());
        w = TestWorlds.own(w, CFG, WORKS, "light_manufacturing", 100, 127, Map.of("civ", 400.0, "food", 400.0, "iron", 400.0), Map.of());
        return w;
    }

    private static double cap(World w) {
        var ctx = new org.hastingtx.empire.engine.update.Ctx(w, CFG, COM, 0);
        return Math.floor(ctx.capacity(w.sector(WORKS), IRON));
    }

    /** The works already holding all but 600 of its ceiling, so there is room for exactly 600 more. */
    private static World worksWithRoomFor600() {
        World w = world();
        Sector works = w.sector(WORKS);
        return w.withSector(works.withStock(works.stock().with(IRON, cap(w) - 600)));
    }

    @Test
    void aMoveCarriesOnlyWhatTheDestinationCanHold() {
        World w = worksWithRoomFor600();
        double ceiling = cap(w);
        double roomLeft = 600;

        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Move(SHED, WORKS, "iron", 4000));
        assertThat(r.ok()).as(r.error()).isTrue();

        assertThat(r.world().sector(WORKS).stock().get(IRON))
                .describedAs("filled to the ceiling and no further")
                .isEqualTo(ceiling);
        assertThat(r.world().sector(SHED).stock().get(IRON))
                .describedAs("the rest never left the warehouse")
                .isEqualTo(4500 - roomLeft);
        assertThat(r.info()).contains("has room for no more");
    }

    @Test
    void nothingIsDestroyedByTheUpdateAfterAMove() {
        World w = new CommandExecutor(CFG).execute(worksWithRoomFor600(), 0, new Command.Move(SHED, WORKS, "iron", 4000)).world();
        var r = Update.run(w, CFG, 5);
        assertThat(r.events().stream().filter(e -> "spoilage".equals(e.type()) && e.at() != null && e.at().equals(WORKS)))
                .describedAs("a command the player just typed must not cause spoilage")
                .isEmpty();
    }

    @Test
    void aFullDestinationRefusesTheMoveAndSaysWhy() {
        World w = world();
        Sector works = w.sector(WORKS);
        w = w.withSector(works.withStock(works.stock().with(IRON, cap(w))));

        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Move(SHED, WORKS, "iron", 100));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("full of iron");
    }

    @Test
    void exploreWillNotMarchMorePeopleThanTheSectorHolds() {
        World w = world();
        Coord empty = Hex.stepRaw(CAP, 3, 1);
        assertThat(w.sector(empty).owned()).isFalse();
        Sector src = w.sector(CAP);
        w = w.withSector(src.withStock(src.stock().with(COM.civ, 9000)));

        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Explore(CAP, empty, 5000));
        assertThat(r.ok()).describedAs("5000 civilians into a sector that holds far fewer").isFalse();
        assertThat(r.error()).contains("holds");
    }

    @Test
    void aMoveThatFitsIsUnaffected() {
        CommandResult r = new CommandExecutor(CFG).execute(world(), 0, new Command.Move(SHED, WORKS, "iron", 200));
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.world().sector(WORKS).stock().get(IRON)).isEqualTo(600.0);   // 400 there + 200 moved
        assertThat(r.info()).doesNotContain("room for no more");
    }
}
