package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Issue #141: the commodity market, as Wolfpack Empire's MARKET option had it — KNOWN commands/sell.c, buy.c, rese.c and
 * check_market. Every update here also passes the apply step's exact conservation check, with goods on the market counted.
 */
class MarketTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord OURS = Hex.stepRaw(CAP, 0, 1);       // our warehouse
    private static final Coord THEIRS = Hex.stepRaw(CAP, 3, 1);     // their warehouse, inside the same disc
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int IRON = COM.index("iron");
    private static final int DELAY = CFG.economy().market().delayUpdates();

    private static World world() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = w.withCountry(w.country(0).withCash(10000));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", THEIRS, 10000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = TestWorlds.own(w, CFG, OURS, "warehouse", 100, 100, Map.of("civ", 100.0, "food", 100.0, "iron", 1000.0), Map.of());
        w = TestWorlds.own(w, CFG, THEIRS, "warehouse", 100, 100, Map.of("civ", 100.0, "food", 100.0), Map.of());
        return w.withSector(w.sector(THEIRS).withOwner(1));
    }

    private static World ok(World w, int who, Command c) {
        CommandResult r = EX.execute(w, who, c);
        assertThat(r.error()).as(r.error()).isNull();
        return r.world();
    }

    private static World updates(World w, int n) {
        for (int i = 0; i < n; i++) w = Update.run(w, CFG, 100 + i).next();
        return w;
    }

    @Test
    void goodsLeaveTheSectorWhenListedAndSellToTheHighBidder() {
        World w = ok(world(), 0, new Command.Sell(OURS, "iron", 400, 2.0));
        assertThat(w.sector(OURS).stock().get(IRON)).as("out of the warehouse at once").isEqualTo(600);
        MarketLot lot = w.market().get(0);
        assertThat(lot.amount()).isEqualTo(400);
        assertThat(lot.settles()).isEqualTo(w.updateNumber() + DELAY);

        w = ok(w, 1, new Command.Buy(lot.id(), 2.10, THEIRS));
        double ourCash = w.country(0).cash(), theirCash = w.country(1).cash();
        UpdateResult last = null;
        for (int i = 0; i < DELAY; i++) { last = Update.run(w, CFG, 200 + i); w = last.next(); }
        assertThat(w.market()).as("sold").isEmpty();
        assertThat(w.sector(THEIRS).stock().get(IRON)).as("in the buyer's warehouse").isEqualTo(400);
        assertThat(last.events()).anySatisfy(e -> assertThat(e.type()).isEqualTo("market_sale"));
        // the money moved, on top of whatever else the updates did to each treasury: compare with a world where nothing sold
        World none = updates(ok(world(), 0, new Command.Sell(OURS, "iron", 400, 2.0)), DELAY);
        assertThat(w.country(0).cash() - none.country(0).cash()).as("the seller is paid the price").isCloseTo(840, within(1e-6));
        assertThat(w.country(1).cash() - none.country(1).cash()).as("the buyer pays it").isCloseTo(-840, within(1e-6));
        assertThat(ourCash).isEqualTo(world().country(0).cash());
        assertThat(theirCash).as("nothing is taken at bid time").isEqualTo(10000);
    }

    @Test
    void aLotNobodyBidsOnNeverSells() {
        World w = updates(ok(world(), 0, new Command.Sell(OURS, "iron", 100, 5.0)), DELAY + 3);
        assertThat(w.market()).hasSize(1);
    }

    @Test
    void whatYouCanSellAndWhere() {
        World w = world();
        assertThat(EX.execute(w, 0, new Command.Sell(CAP, "iron", 10, 1)).error()).contains("through a harbor or warehouse");
        assertThat(EX.execute(w, 0, new Command.Sell(OURS, "civ", 10, 1)).error()).contains("civ cannot be sold");
        assertThat(EX.execute(w, 0, new Command.Sell(OURS, "iron", 10, 1001)).error()).contains("at most $1000.00");
        assertThat(EX.execute(w, 0, new Command.Sell(OURS, "iron", 10, 0)).error()).contains("more than $0");
        assertThat(EX.execute(w, 0, new Command.Sell(THEIRS, "food", 10, 1)).error()).contains("you do not own");
        World run = w.withSector(w.sector(OURS).withDesignation("warehouse", 50));
        assertThat(EX.execute(run, 0, new Command.Sell(OURS, "iron", 10, 1)).error()).contains("it trades at 60");
        // a negative number keeps that many
        World kept = ok(w, 0, new Command.Sell(OURS, "iron", -250, 1));
        assertThat(kept.sector(OURS).stock().get(IRON)).isEqualTo(250);
        assertThat(kept.market().get(0).amount()).isEqualTo(750);
        assertThat(EX.execute(w, 0, new Command.Sell(OURS, "iron", -1000, 1)).error()).contains("nothing to sell");
        assertThat(EX.execute(w, 0, new Command.Sell(OURS, "iron", -0.5, 1)).error()).as("not 'all of it'").contains("whole number");
    }

    @Test
    void whatABidMustBe() {
        World w = ok(world(), 0, new Command.Sell(OURS, "iron", 400, 2.0));
        long id = w.market().get(0).id();
        assertThat(EX.execute(w, 0, new Command.Buy(id, 3, OURS)).error()).as("your own lot").contains("is yours");
        assertThat(EX.execute(w, 1, new Command.Buy(id, 2.04, THEIRS)).error()).as("five cents over, even the first bid").contains("at least $2.05");
        assertThat(EX.execute(w, 1, new Command.Buy(id, 30, THEIRS)).error()).as("12,000 against 10,000 in the bank").contains("you have $10000.00");
        assertThat(EX.execute(w, 1, new Command.Buy(id, 2.5, CAP)).error()).contains("you do not own");
        assertThat(EX.execute(w, 1, new Command.Buy(99, 2.5, THEIRS)).error()).contains("no lot 99");
        assertThat(EX.execute(w, 1, new Command.Buy(id, 1000.5, THEIRS)).error()).contains("no unit sells for more than $1000.00");
        World bid = ok(w, 1, new Command.Buy(id, 2.05, THEIRS));
        assertThat(EX.execute(bid, 0, new Command.ResetLot(id, 1)).error()).as("out of the seller's hands").contains("has a bid");
        // a second lot they could not also pay for, on top of the first
        World two = ok(ok(bid, 0, new Command.Sell(OURS, "iron", 600, 2.0)), 0, new Command.Sell(OURS, "food", 1, 1.0));
        long second = two.market().get(1).id();
        assertThat(EX.execute(two, 1, new Command.Buy(second, 16, THEIRS)).error()).contains("already the high bidder");
    }

    @Test
    void aLateBidGivesTheOthersAnUpdate() {
        World w = ok(world(), 0, new Command.Sell(OURS, "iron", 100, 2.0));
        long id = w.market().get(0).id();
        w = updates(w, DELAY - 1);   // the next update would sell it
        w = ok(w, 1, new Command.Buy(id, 2.05, THEIRS));
        assertThat(w.lot(id).settles() - w.updateNumber()).isEqualTo(CFG.economy().market().snipeUpdates() + 1);
        w = updates(w, 1);
        assertThat(w.market()).as("not yet").hasSize(1);
        w = updates(w, 1);
        assertThat(w.market()).isEmpty();
    }

    @Test
    void aSaleTheBuyerCannotPayForGoesBackOnTheMarket() {
        World w = ok(world(), 0, new Command.Sell(OURS, "iron", 400, 2.0));
        long id = w.market().get(0).id();
        w = ok(w, 1, new Command.Buy(id, 20, THEIRS));
        w = w.withCountry(w.country(1).withCash(100));   // spent it elsewhere since
        UpdateResult r = null;
        for (int i = 0; i < DELAY; i++) { r = Update.run(w, CFG, 300 + i); w = r.next(); }
        assertThat(w.lot(id)).isNotNull();
        assertThat(w.lot(id).bid()).isFalse();
        assertThat(w.lot(id).price()).as("at the price it reached").isEqualTo(20);
        assertThat(w.sector(THEIRS).stock().get(IRON)).isZero();
        assertThat(r.events()).anySatisfy(e -> assertThat(e.message()).contains("fell through"));
        assertThat(r.events()).filteredOn(e -> e.country() == 0).allSatisfy(e -> assertThat(e.message()).as("the seller is not told where the buyer's warehouse is").doesNotContain(THEIRS.x() + "," + THEIRS.y()).doesNotContain("$"));
    }

    @Test
    void anUnbidLotIsCheapenedOrTakenBack() {
        World w = ok(world(), 0, new Command.Sell(OURS, "iron", 400, 2.0));
        long id = w.market().get(0).id();
        w = updates(w, 2);
        assertThat(EX.execute(w, 0, new Command.ResetLot(id, 3)).error()).contains("lower than $2.00");
        World cheaper = ok(w, 0, new Command.ResetLot(id, 1.5));
        assertThat(cheaper.lot(id).price()).isEqualTo(1.5);
        assertThat(cheaper.lot(id).settles()).as("its time starts again").isEqualTo(w.updateNumber() + DELAY);
        assertThat(EX.execute(w, 1, new Command.ResetLot(id, 1)).error()).contains("no lot " + id + " of yours");
        World back = ok(w, 0, new Command.ResetLot(id, 0));
        assertThat(back.market()).isEmpty();
        assertThat(back.sector(OURS).stock().get(IRON)).isEqualTo(1000);
    }
}
