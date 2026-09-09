package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Flow;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Issue #45: standing delivery orders — above a threshold, one hex in a direction, every update. */
class DeliverTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int IRON = COM.index("iron");
    private static final Coord CAP = TestWorlds.CENTER;

    private static World base() { return TestWorlds.disc(CFG, 3, Map.of("civ", 100.0, "food", 1000.0, "iron", 500.0)); }
    private static World own(World w, Coord at, double iron) {
        return TestWorlds.own(w, CFG, at, "agribusiness", 100, 127, Map.of("civ", 100.0, "food", 500.0, "iron", iron), Map.of());
    }
    private static World order(World w, Coord at, int dir, double thr) { Sector s = w.sector(at); return w.withSector(s.withDeliver(s.deliver().with(IRON, dir, thr))); }

    @Test
    void aboveTheThresholdMovesOneHexThatWayAndTheSenderPays() {
        Coord a = Hex.stepRaw(CAP, 0, 1), b = Hex.stepRaw(CAP, 0, 2);
        World w = order(own(own(base(), a, 500), b, 0), a, 0, 100);
        UpdateResult r = Update.run(w, CFG, 3);
        assertThat(r.next().sector(a).stock().get(IRON)).isCloseTo(100, within(1e-6));
        assertThat(r.next().sector(b).stock().get(IRON)).isCloseTo(400, within(1e-6));
        assertThat(r.next().sector(a).mobility()).isLessThan(127);          // sender paid
        assertThat(r.next().sector(b).mobility()).isCloseTo(127, within(1e-6));   // receiver did not
        Flow f = r.flows().stream().filter(x -> x.kind().equals("deliver")).findFirst().orElseThrow();
        assertThat(f.completed()).isTrue();
        assertThat(f.qtyMoved()).isCloseTo(400, within(1e-6));
    }

    @Test
    void aChainAdvancesOneHopPerUpdate() {
        Coord a = Hex.stepRaw(CAP, 0, 1), b = Hex.stepRaw(CAP, 0, 2), c = Hex.stepRaw(CAP, 0, 3);
        World w = order(order(own(own(own(base(), a, 300), b, 0), c, 0), a, 0, 0), b, 0, 0);
        World n1 = Update.run(w, CFG, 3).next();
        assertThat(n1.sector(b).stock().get(IRON)).isCloseTo(300, within(1e-6));
        assertThat(n1.sector(c).stock().get(IRON)).isCloseTo(0, within(1e-6));
        World n2 = Update.run(n1, CFG, 4).next();
        assertThat(n2.sector(b).stock().get(IRON)).isCloseTo(0, within(1e-6));
        assertThat(n2.sector(c).stock().get(IRON)).isCloseTo(300, within(1e-6));
    }

    @Test
    void allSixDirectionsBehaveAlike() {
        double[] got = new double[6], mob = new double[6];
        for (int d = 0; d < 6; d++) {
            World w = base();
            for (int k = 0; k < 6; k++) w = own(w, Hex.stepRaw(CAP, k, 1), 0);
            w = order(w, CAP, d, 100);
            World n = Update.run(w, CFG, 3).next();
            got[d] = n.sector(Hex.stepRaw(CAP, d, 1)).stock().get(IRON);
            mob[d] = n.sector(CAP).mobility();
            for (int k = 0; k < 6; k++) if (k != d) assertThat(n.sector(Hex.stepRaw(CAP, k, 1)).stock().get(IRON)).isCloseTo(0, within(1e-6));
        }
        for (int d = 1; d < 6; d++) { assertThat(got[d]).isCloseTo(got[0], within(1e-6)); assertThat(mob[d]).isCloseTo(mob[0], within(1e-6)); }
        assertThat(got[0]).isCloseTo(400, within(1e-6));
    }

    @Test
    void nothingMovesIntoASectorYouDoNotOwn() {
        Coord a = Hex.stepRaw(CAP, 0, 1);
        World w = order(own(base(), a, 500), a, 0, 100);     // the hex east of a is plains, unowned
        UpdateResult r = Update.run(w, CFG, 3);
        assertThat(r.next().sector(a).stock().get(IRON)).isCloseTo(500, within(1e-6));
        assertThat(r.flows()).noneMatch(f -> f.kind().equals("deliver"));
    }

    @Test
    void deliverAndDistributionShareOneSurplusWithoutOverdrawingIt() {
        // a pushes iron east by order AND has a distribution threshold that pushes surplus to the capital: the two draw on one stock
        Coord a = Hex.stepRaw(CAP, 0, 1), b = Hex.stepRaw(CAP, 0, 2);
        World w = own(own(base(), a, 500), b, 0);
        Sector s = w.sector(a);
        double[] th = s.thresholds().clone(); th[IRON] = 200;
        w = w.withSector(s.withThresholds(th).withDeliver(s.deliver().with(IRON, 0, 100)));
        World n = Update.run(w, CFG, 3).next();
        double left = n.sector(a).stock().get(IRON), east = n.sector(b).stock().get(IRON), toCap = n.sector(CAP).stock().get(IRON) - 500;
        assertThat(left).isGreaterThanOrEqualTo(200 - 1e-6);           // the stricter keep wins
        assertThat(left + east + toCap).isCloseTo(500, within(1e-6));  // nothing created or lost
        assertThat(east).isGreaterThan(0);
        assertThat(toCap).isGreaterThan(0);
    }

    @Test
    void commandValidatesSetsAndClears() {
        Coord a = Hex.stepRaw(CAP, 0, 1);
        World w = own(base(), a, 500);
        CommandExecutor ex = new CommandExecutor(CFG);
        CommandResult r = ex.execute(w, 0, new Command.Deliver(a, "iron", 1, 300));
        assertThat(r.error()).isNull();
        assertThat(r.world().sector(a).deliver().has(IRON)).isTrue();
        assertThat(r.world().sector(a).deliver().dir(IRON)).isEqualTo(1);
        assertThat(r.world().sector(a).deliver().threshold(IRON)).isEqualTo(300);
        assertThat(r.info()).contains("until you own");                // north-east of a is unowned
        assertThat(ex.execute(w, 0, new Command.Deliver(a, "iron", 7, 0)).error()).contains("direction");
        assertThat(ex.execute(w, 0, new Command.Deliver(a, "iron", 0, -5)).error()).contains("threshold");
        assertThat(ex.execute(w, 0, new Command.Deliver(a, "plutonium", 0, 5)).error()).contains("unknown commodity");
        CommandResult c = ex.execute(r.world(), 0, new Command.Deliver(a, "iron", null, 0));
        assertThat(c.error()).isNull();
        assertThat(c.world().sector(a).deliver().has(IRON)).isFalse();
        assertThat(Hex.parseDir("ne")).isEqualTo(1);
        assertThat(Hex.parseDir("north-east")).isEqualTo(1);
        assertThat(Hex.parseDir("u")).isEqualTo(1);
        assertThat(Hex.parseDir("g")).isEqualTo(3);
        assertThat(Hex.parseDir("x")).isEqualTo(-1);
    }
}
