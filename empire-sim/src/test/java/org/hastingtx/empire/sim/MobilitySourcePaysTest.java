package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Flow;
import org.hastingtx.empire.engine.update.Routes;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Issue #36: the sending sector pays a shipment's whole mobility. A destination with no
 * mobility of its own (a busy distribution centre) can still receive, by hand or by distribution.
 */
class MobilitySourcePaysTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord A = new Coord(CAP.x() + 1, CAP.y());   // adjacent plains sector; moving 1 hcm into plains at 100% costs 0.2

    private static World world(double aMobility, Map<String, Double> aThresholds) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 100.0, "food", 1000.0));
        w = w.withSector(w.sector(CAP).withMobility(0));
        return TestWorlds.own(w, CFG, A, "agribusiness", 100, aMobility, /* normal packing: 1 hcm weighs 1 */ Map.of("civ", 100.0, "food", 500.0, "hcm", 5000.0), aThresholds);
    }

    @Test
    void handMoveIntoAZeroMobilityCapitalSucceedsAndChargesTheSender() {
        assertThat(CFG.distribution().sourcePays()).isTrue();
        World w = world(127, Map.of());
        Commodities com = Commodities.of(CFG);
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Move(A, CAP, "hcm", 100));
        assertThat(r.error()).isNull();
        assertThat(r.world().sector(CAP).stock().get(com.index("hcm"))).isCloseTo(100, within(1e-9));
        assertThat(r.world().sector(A).stock().get(com.index("hcm"))).isCloseTo(4900, within(1e-9));
        assertThat(r.world().sector(CAP).mobility()).isEqualTo(0.0);          // the receiver paid nothing
        assertThat(r.world().sector(A).mobility()).isLessThan(127.0);          // the sender paid
    }

    @Test
    void handMoveFailsNamingTheSenderWhenItHasNoMobility() {
        World w = world(0, Map.of());
        w = w.withSector(w.sector(CAP).withMobility(127));
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Move(A, CAP, "hcm", 100));
        assertThat(r.error()).contains("no mobility in " + A);
    }

    @Test
    void estimateAgreesWithTheCommand() {
        World w = world(127, Map.of());
        Commodities com = Commodities.of(CFG);
        Routes.Estimate e = Routes.move(w, CFG, 0, A, CAP, com.index("hcm"), 100);
        assertThat(e.ok()).isTrue();
        assertThat(e.arrivesQty()).isCloseTo(100, within(1e-9));
        assertThat(e.heldQty()).isCloseTo(0, within(1e-9));
        // more than the sender can pay for: what fits moves, the rest is held at the origin
        Routes.Estimate big = Routes.move(w, CFG, 0, A, CAP, com.index("hcm"), 5000);
        assertThat(big.ok()).isTrue();
        assertThat(big.arrivesQty() + big.heldQty()).isCloseTo(5000, within(1e-9));
        assertThat(big.heldQty()).isGreaterThan(0);
        assertThat(big.holdsAt()).isEqualTo(A);
    }

    @Test
    void distributionIntoAZeroMobilityCentreCompletes() {
        // A pushes all its hcm (threshold 0) to the capital, its centre. 5000 hcm costs the sender
        // 5000 × 0.2 ÷ 10 = 100 mobility: more than the centre's 60 accrual could pay under the old rule.
        World w = world(127, Map.of("hcm", 0.0));
        UpdateResult r = Update.run(w, CFG, 1);
        Flow f = r.flows().stream().filter(x -> x.commodity() == Commodities.of(CFG).index("hcm") && x.kind().equals("distribution")).findFirst().orElseThrow();
        assertThat(f.completed()).as(f.toString()).isTrue();
        assertThat(f.qtyMoved()).isCloseTo(5000, within(1e-6));
        assertThat(r.next().sector(CAP).mobility()).isCloseTo(60, within(1e-6));   // accrual only; the centre paid nothing
        assertThat(r.next().sector(A).mobility()).isCloseTo(27, within(1e-6));     // 127 − 100, no room to accrue
    }
}
