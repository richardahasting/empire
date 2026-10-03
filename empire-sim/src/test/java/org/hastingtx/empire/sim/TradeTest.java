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
import static org.assertj.core.api.Assertions.within;

/**
 * Issue #141, part 2: ships, planes and land units for sale — KNOWN Wolfpack Empire commands/set.c, trad.c and
 * check_trade, trdsub.c. Every update here passes the apply step's exact conservation check.
 */
class TradeTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOUR = Hex.stepRaw(CAP, 0, 2);      // ours, on the rim
    private static final Coord FIELD = Hex.stepRaw(CAP, 1, 1);        // ours
    private static final Coord THEIR_FIELD = Hex.stepRaw(CAP, 3, 1);
    private static final Coord THEIR_HQ = Hex.stepRaw(CAP, 4, 1);
    private static final CommandExecutor EX = new CommandExecutor(CFG);
    private static final int DELAY = CFG.economy().market().tradeDelayUpdates();

    private static World world() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 5000.0));
        w = w.withCountry(w.country(0).withCash(50000));
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", THEIR_FIELD, 50000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(cs);
        w = TestWorlds.own(w, CFG, HARBOUR, "harbor", 100, 100, Map.of("civ", 100.0, "food", 100.0), Map.of());
        w = TestWorlds.own(w, CFG, FIELD, "airfield", 100, 100, Map.of("civ", 100.0, "food", 100.0), Map.of());
        w = TestWorlds.own(w, CFG, THEIR_FIELD, "airfield", 100, 100, Map.of("civ", 100.0, "food", 100.0), Map.of());
        w = TestWorlds.own(w, CFG, THEIR_HQ, "headquarters", 100, 100, Map.of("civ", 100.0, "food", 100.0), Map.of());
        w = w.withSector(w.sector(THEIR_FIELD).withOwner(1)).withSector(w.sector(THEIR_HQ).withOwner(1));
        var c = CFG.units().ships().shipClass("cargo_ship");
        Ship s = new Ship(1, 0, "cargo_ship", "Rose", HARBOUR, 100, Stocks.zero(COM.size()).with(COM.index("iron"), 50), null, null, 0, "", 50, null, null, c.tankOr0(), c.crewOr0());
        w = w.withShips(List.of(s), 2);
        w = w.withPlane(new Plane(1, 0, "bomber", FIELD, 100, 120, 0, ""));
        return w.withUnit(new LandUnit(1, 0, "infantry", CAP, 100, Stocks.zero(COM.size()).with(COM.mil, 20), 10, 60, 0, "", 0));
    }

    private static World ok(World w, int who, Command c) {
        CommandResult r = EX.execute(w, who, c);
        assertThat(r.error()).as(r.error()).isNull();
        return r.world();
    }

    private static World updates(World w, int n) { for (int i = 0; i < n; i++) w = Update.run(w, CFG, 500 + i).next(); return w; }

    @Test
    void aShipChangesHandsWhereSheLiesWithHerHold() {
        World w = ok(world(), 0, new Command.SetPrice("ship", List.of(1L), 20000));
        TradeLot lot = w.trades().get(0);
        w = ok(w, 1, new Command.Trade(lot.id(), 21000, null));
        w = updates(w, DELAY);
        Ship s = w.ship(1);
        assertThat(s.owner()).isEqualTo(1);
        assertThat(s.at()).as("where she lay").isEqualTo(HARBOUR);
        assertThat(s.stock().get(COM.index("iron"))).as("with her hold").isEqualTo(50);
        assertThat(s.mission()).isNull();
        assertThat(w.trades()).isEmpty();
        World none = updates(world(), DELAY);
        assertThat(w.country(0).cash() - none.country(0).cash()).as("the seller keeps 99%").isCloseTo(21000 * 0.99, within(1e-6));
        assertThat(w.country(1).cash() - none.country(1).cash()).as("the buyer pays the price").isCloseTo(-21000, within(1e-6));
    }

    @Test
    void aPlaneFliesToTheBuyersAirfieldAndAUnitGoesToTheirHeadquarters() {
        World w = ok(ok(world(), 0, new Command.SetPrice("plane", List.of(1L), 3000)), 0, new Command.SetPrice("unit", List.of(1L), 2000));
        long planeLot = w.onTheBlock(TradeLot.PLANE, 1).id(), unitLot = w.onTheBlock(TradeLot.UNIT, 1).id();
        assertThat(EX.execute(w, 1, new Command.Trade(planeLot, 3001, null)).error()).contains("needs an airfield of yours");
        assertThat(EX.execute(w, 1, new Command.Trade(planeLot, 3001, THEIR_HQ)).error()).contains("is not an airfield of yours");
        assertThat(EX.execute(w, 1, new Command.Trade(planeLot, 3000, THEIR_FIELD)).error()).as("more than the price").contains("more than $3000.00");
        assertThat(EX.execute(w, 1, new Command.Trade(planeLot, 3001.5, THEIR_FIELD)).error()).contains("whole dollars");
        w = ok(ok(w, 1, new Command.Trade(planeLot, 3001, THEIR_FIELD)), 1, new Command.Trade(unitLot, 2001, THEIR_HQ));
        w = updates(w, DELAY);
        assertThat(w.plane(1).owner()).isEqualTo(1);
        assertThat(w.plane(1).at()).isEqualTo(THEIR_FIELD);
        LandUnit u = w.unit(1);
        assertThat(u.owner()).isEqualTo(1);
        assertThat(u.at()).isEqualTo(THEIR_HQ);
        assertThat(u.stock().get(COM.mil)).as("its soldiers go with it").isGreaterThan(0);
    }

    @Test
    void whatIsForSaleDoesNothing() {
        World w = ok(world(), 0, new Command.SetPrice("ship", List.of(1L), 20000));
        assertThat(EX.execute(w, 0, new Command.Sail(1, Hex.stepRaw(CAP, 0, 4))).error()).contains("ship #1 is for sale").contains("set ship 1 0");
        World p = ok(world(), 0, new Command.SetPrice("plane", List.of(1L), 100));
        assertThat(EX.execute(p, 0, new Command.Recon(1, CAP)).error()).contains("plane #1 is for sale");
        World u = ok(world(), 0, new Command.SetPrice("unit", List.of(1L), 100));
        assertThat(EX.execute(u, 0, new Command.March(1, HARBOUR)).error()).contains("unit #1 is for sale");
        // a ship sent off before she was put up holds where she is
        World sent = world().withShip(world().ship(1).withDest(Hex.stepRaw(CAP, 0, 5)));
        sent = updates(ok(sent, 0, new Command.SetPrice("ship", List.of(1L), 20000)), 2);
        assertThat(sent.ship(1).at()).isEqualTo(HARBOUR);
        assertThat(sent.ship(1).note()).contains("for sale");
    }

    @Test
    void whatCannotBeSold() {
        World w = world();
        assertThat(EX.execute(w, 0, new Command.SetPrice("zeppelin", List.of(1L), 10)).error()).contains("ship|plane|unit");
        assertThat(EX.execute(w, 1, new Command.SetPrice("ship", List.of(1L), 10)).error()).contains("no ship #1 of yours");
        assertThat(EX.execute(w, 0, new Command.SetPrice("ship", List.of(1L), 10.5)).error()).contains("whole dollars");
        World civs = w.withShip(w.ship(1).withStock(w.ship(1).stock().with(COM.civ, 5)));
        assertThat(EX.execute(civs, 0, new Command.SetPrice("ship", List.of(1L), 10)).error()).contains("people are not for sale");
        World aboard = w.withUnit(w.unit(1).withShip(1));
        assertThat(EX.execute(aboard, 0, new Command.SetPrice("unit", List.of(1L), 10)).error()).contains("put it ashore first");
    }

    @Test
    void offTheMarketAndStartedAfresh() {
        World w = ok(world(), 0, new Command.SetPrice("ship", List.of(1L), 20000));
        long id = w.trades().get(0).id();
        w = ok(w, 1, new Command.Trade(id, 20001, null));
        World again = ok(w, 0, new Command.SetPrice("ship", List.of(1L), 15000));
        assertThat(again.trade(id).bid()).as("setting a price again voids the bid, as the original did").isFalse();
        World off = ok(w, 0, new Command.SetPrice("ship", List.of(1L), 0));
        assertThat(off.trades()).isEmpty();
        assertThat(EX.execute(w, 1, new Command.Trade(id, 1, null)).error()).contains("more than");
    }

    @Test
    void aBuyerWhoCannotPayLosesTheLotAndTheSellerKeepsIt() {
        World w = ok(world(), 0, new Command.SetPrice("ship", List.of(1L), 20000));
        w = ok(w, 1, new Command.Trade(w.trades().get(0).id(), 20001, null));
        w = w.withCountry(w.country(1).withCash(10));
        w = updates(w, DELAY);
        assertThat(w.ship(1).owner()).isZero();
        assertThat(w.trades()).as("taken off the market").isEmpty();
    }

    @Test
    void aLotGoesWithWhatItSold() {
        World w = ok(world(), 0, new Command.SetPrice("ship", List.of(1L), 20000));
        w = updates(w.withoutShip(1), 1);
        assertThat(w.trades()).isEmpty();
    }
}
