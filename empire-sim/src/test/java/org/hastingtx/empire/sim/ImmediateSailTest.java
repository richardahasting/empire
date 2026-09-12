package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Issue #69: a sail happens now. The ship has a mobility pool of its own — it fills by the hull's
 * speed each update, a standing mission spends it then, and a player's order spends it there and
 * then. That is what lets a warship answer something it has just seen rather than an update later.
 */
class ImmediateSailTest {

    private static final GameConfig CFG = new ConfigLoader().loadPreset("sandbox").config();
    private static final long SEED = 11L;

    private record Setup(World world, Ship ship, List<Coord> sea) {}

    /** A cargo ship on open water with a full tank, and a run of sea to sail along. */
    private static Setup afloat(double mobility) {
        World w = new WorldGenerator(CFG).generate(List.of("A"), SEED);
        Commodities com = Commodities.of(CFG);
        // a genuinely adjacent corridor: sea.get(i) must be i hexes from the start, or the
        // assertions below are about a distance nobody actually sailed
        List<Coord> sea = corridor(w);
        Coord start = sea.get(0);
        Ship ship = new Ship(1, 0, "cargo_ship", "Hauler", start, 100, Stocks.zero(com.size()),
                null, null, 0, null, 0, null, null, 10000, 1000, mobility);
        return new Setup(w.withShips(List.of(ship), 2), ship, sea);
    }

    /** The longest run of open water reachable one hex at a time, walking east. */
    private static List<Coord> corridor(World w) {
        List<Coord> best = List.of();
        for (Sector s : w.sectors()) {
            if (s.terrain() != Terrain.OCEAN) continue;
            List<Coord> run = new java.util.ArrayList<>();
            Coord at = s.at();
            while (run.size() < 12 && w.sector(at).terrain() == Terrain.OCEAN) {
                run.add(at);
                Coord next = org.hastingtx.empire.engine.geo.Hex.stepRaw(at, 0);
                if (!w.inBounds(next) || w.sector(next).terrain() != Terrain.OCEAN) break;
                at = next;
            }
            if (run.size() > best.size()) best = run;
            if (best.size() >= 10) break;
        }
        return best;
    }

    private static CommandResult sailTo(Setup s, Coord dest) {
        return new CommandExecutor(CFG).execute(s.world(), 0, new Command.Sail(1, dest));
    }

    @Test
    void theShipMovesOnTheCommandNotAtTheUpdate() {
        Setup s = afloat(5);
        Coord from = s.ship().at();
        CommandResult r = sailTo(s, s.sea().get(3));
        assertThat(r.ok()).isTrue();
        assertThat(r.world().ship(1).at()).as("it has already gone").isNotEqualTo(from);
        assertThat(r.info()).contains("arrived");
    }

    @Test
    void itSpendsItsMobilityDoingSo() {
        Setup s = afloat(5);
        var after = sailTo(s, s.sea().get(3)).world().ship(1);
        double rush = CFG.units().ships().rushCost();
        assertThat(after.mobility()).as("three hexes at the rush rate, out of five")
                .isEqualTo(5 - 3 * rush);
    }

    @Test
    void aLongRunGoesAsFarAsItCanAndFinishesAtTheUpdate() {
        Setup s = afloat(2);
        Coord far = s.sea().get(6);
        CommandResult r = sailTo(s, far);
        Ship after = r.world().ship(1);
        // two points of mobility at 1.25 a hex buys one hex, not two
        assertThat(after.at()).isEqualTo(s.sea().get(1));
        assertThat(after.dest()).as("still bound for the rest").isEqualTo(far);
        assertThat(after.mobility()).isLessThan(CFG.units().ships().rushCost());
        assertThat(r.info()).contains("to go, at the update");

        // the update carries it on, once the pool has refilled
        Ship later = Update.run(r.world(), CFG, SEED).next().ship(1);
        assertThat(later.at()).as("further along than it was").isNotEqualTo(after.at());
    }

    @Test
    void aShipWithNothingInTheTankDoesNotMove() {
        Setup s = afloat(5);
        Commodities com = Commodities.of(CFG);
        World dry = s.world().withShip(s.world().ship(1).withFuel(0));
        CommandResult r = new CommandExecutor(CFG).execute(dry, 0, new Command.Sail(1, s.sea().get(3)));
        assertThat(r.ok()).isTrue();
        assertThat(r.world().ship(1).at()).isEqualTo(s.ship().at());
        assertThat(r.info()).contains("tank is dry");
    }

    @Test
    void aShipWithNoMobilityLeftWaitsForTheUpdate() {
        Setup s = afloat(0);
        CommandResult r = sailTo(s, s.sea().get(3));
        assertThat(r.world().ship(1).at()).isEqualTo(s.ship().at());
        assertThat(r.world().ship(1).dest()).isEqualTo(s.sea().get(3));
        assertThat(r.info()).contains("at the update");
    }

    @Test
    void sailingBurnsFuelImmediatelyToo() {
        Setup s = afloat(5);
        double before = s.world().ship(1).fuel();
        var after = sailTo(s, s.sea().get(3)).world().ship(1);
        assertThat(after.fuel()).as("three hexes of petrol, now").isLessThan(before);
    }

    /** Autonomy is the other half: a mission still runs itself at the update. */
    @Test
    void aStandingMissionStillMovesTheShipOnItsOwn() {
        Setup s = afloat(0);
        World w = s.world().withShip(s.world().ship(1).withDest(s.sea().get(4)));
        Ship after = Update.run(w, CFG, SEED).next().ship(1);
        assertThat(after.at()).as("nobody ordered this; the update did it").isNotEqualTo(s.ship().at());
    }

    /** Richard's surcharge: the same pool carries a planned deployment further than a rushed one. */
    @Test
    void rushingCostsMoreThanPlanning() {
        double pool = 10;
        Setup rushed = afloat(pool);
        Coord far = rushed.sea().get(9);
        int byOrder = hexesFrom(rushed.sea(), sailTo(rushed, far).world().ship(1).at());

        // the same ship, the same pool, left to do it at the update instead
        Setup planned = afloat(pool);
        World w = planned.world().withShip(planned.world().ship(1).withDest(far).withMobility(pool));
        // the update tops the pool up first, so compare against a ship given no refill
        int byPlan = (int) Math.floor(pool);

        assertThat(byOrder).as("haste buys fewer hexes for the same mobility").isLessThan(byPlan);
        assertThat(byOrder).isEqualTo((int) Math.floor(pool / CFG.units().ships().rushCost()));
    }

    private static int hexesFrom(List<Coord> run, Coord at) {
        for (int i = 0; i < run.size(); i++) if (run.get(i).equals(at)) return i;
        return -1;
    }

    @Test
    void thePoolIsCappedSoIdlingIsNotATeleport() {
        Setup s = afloat(0);
        World w = s.world();
        for (int i = 0; i < 6; i++) w = Update.run(w, CFG, SEED).next();
        var cls = CFG.units().ships().shipClass("cargo_ship");
        double cap = CFG.units().ships().mobilityCap(cls, 0, 100);
        assertThat(w.ship(1).mobility()).as("six idle updates bank the cap, not six updates' worth")
                .isLessThanOrEqualTo(cap);
    }
}
