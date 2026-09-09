package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Issue #56, phase 1: harbours build ships; ships fit out, fish, cruise, run lanes and sail. */
class ShipsTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int FOOD = COM.food, LCM = COM.index("lcm");
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOR_E = Hex.stepRaw(CAP, 0, 2), HARBOR_W = Hex.stepRaw(CAP, 3, 2);   // on the disc's rim, sea beyond
    private static final Coord SEA_E = Hex.stepRaw(CAP, 0, 3);

    private static World world() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 2000.0));
        w = w.withCountry(w.country(0).withCash(100000));
        for (Coord h : new Coord[] {HARBOR_E, HARBOR_W})
            w = TestWorlds.own(w, CFG, h, "harbor", 100, 127, Map.of("civ", 500.0, "food", 1000.0, "lcm", 500.0, "hcm", 200.0), Map.of("food", 200.0));
        // fishing grounds east of the eastern harbour
        Sector sea = w.sector(SEA_E);
        w = w.withSector(sea.withTerrain(Terrain.OCEAN, 0, new Resources(60, 0, 0, 0, 0)));
        return w;
    }
    private static World withShip(World w, String cls, Coord at, double eff) { return withShip(w, cls, at, eff, 0); }
    private static World withShip(World w, String cls, Coord at, double eff, double tech) {
        Ship s = new Ship(w.nextShipId(), 0, cls, "", at, eff, Stocks.zero(COM.size()), null, null, 0, "", tech, null, null);
        return w.withShips(List.of(s), s.id() + 1);
    }

    @Test
    void speedScalesWithTheTechTheHullWasLaidAt() {
        // cargo ship: speed 3; at tech 100 the multiplier is 2.0, so 6 hexes an update
        World w = withShip(world(), "cargo_ship", HARBOR_E, 100, 100);
        Coord far = Hex.stepRaw(CAP, 0, 8);
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Sail(1, far));
        assertThat(r.error()).isNull();
        World n1 = Update.run(r.world(), CFG, 1).next();
        assertThat(Hex.distanceRaw(n1.ship(1).at(), HARBOR_E)).isEqualTo(6);
        assertThat(CFG.units().ships().range(CFG.units().ships().shipClass("cargo_ship"), 0, 100)).isEqualTo(3);
        assertThat(CFG.units().ships().range(CFG.units().ships().shipClass("cargo_ship"), 300, 100)).isEqualTo(7);   // capped at ×2.5
    }

    @Test
    void aHarbourLaysAHullAndFitsItOutWhileDocked() {
        World w = world();
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.BuildShip(HARBOR_E, "fishing_boat", "Maggie"));
        assertThat(r.error()).isNull();
        assertThat(r.world().ships()).hasSize(1);
        Ship s = r.world().ships().get(0);
        assertThat(s.efficiency()).isEqualTo(20);
        assertThat(s.name()).isEqualTo("Maggie");
        assertThat(r.world().sector(HARBOR_E).stock().get(LCM)).isCloseTo(475, within(1e-9));      // 25 lcm
        assertThat(r.world().country(0).cash()).isCloseTo(100000 - 300, within(1e-9));
        UpdateResult u = Update.run(r.world(), CFG, 1);
        Ship after = u.next().ships().get(0);
        assertThat(after.efficiency()).isCloseTo(40, within(1e-9));                                   // 20 dock points
        assertThat(after.note()).contains("fitted out");
        assertThat(u.notes().get(HARBOR_E.x() + "," + HARBOR_E.y())).anyMatch(l -> l.contains("fitted out"));
        // no tech, no hull
        assertThat(new CommandExecutor(CFG).execute(w, 0, new Command.BuildShip(HARBOR_E, "battleship", null)).error()).contains("tech");
        assertThat(new CommandExecutor(CFG).execute(w, 0, new Command.BuildShip(CAP, "fishing_boat", null)).error()).contains("not a harbour");
    }

    @Test
    void sailingIsSpeedTimesEfficiencyPerUpdateOverSeaOnly() {
        World w = withShip(world(), "cargo_ship", HARBOR_E, 100);
        Coord far = Hex.stepRaw(CAP, 0, 6);
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Sail(1, far));
        assertThat(r.error()).isNull();
        assertThat(r.info()).contains("4 hexes");
        World n1 = Update.run(r.world(), CFG, 1).next();
        assertThat(Hex.distanceRaw(n1.ship(1).at(), HARBOR_E)).isEqualTo(3);                       // speed 3 at 100%
        World n2 = Update.run(n1, CFG, 2).next();
        assertThat(n2.ship(1).at()).isEqualTo(far);
        assertThat(n2.ship(1).dest()).isNull();
        assertThat(n2.ship(1).note()).contains("arrived");
        // land is not navigable
        assertThat(new CommandExecutor(CFG).execute(w, 0, new Command.Sail(1, Hex.stepRaw(CAP, 0, 1))).error()).contains("no sea route");
    }

    @Test
    void fishingBoatsFishTheGroundsAndLandTheCatchAtHome() {
        World w = withShip(world(), "fishing_boat", SEA_E, 100);
        UpdateResult u = Update.run(w, CFG, 1);
        Ship s = u.next().ships().get(0);
        assertThat(s.stock().get(FOOD)).isCloseTo(300, within(1e-6));      // 1.0 × 60 fert × 60 ETUs × 0.1 = 360, hold 300
        assertThat(s.note()).contains("fished").contains("hold full");
        World home = u.next().withShip(s.withDest(HARBOR_E));
        World back = Update.run(home, CFG, 2).next();
        assertThat(back.ship(1).at()).isEqualTo(HARBOR_E);
        World landed = Update.run(back, CFG, 3).next();                     // docked now: auto-unload
        assertThat(landed.ship(1).stock().get(FOOD)).isCloseTo(0, within(1e-6));
        assertThat(landed.sector(HARBOR_E).stock().get(FOOD)).isGreaterThan(back.sector(HARBOR_E).stock().get(FOOD) + 250);
    }

    @Test
    void aLaneShuttlesSurplusBetweenTwoHarbours() {
        World w = withShip(world(), "cargo_ship", HARBOR_E, 100);
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Lane(1, HARBOR_E, HARBOR_W, List.of("food")));
        assertThat(r.error()).as(r.error()).isNull();
        World cur = r.world();
        double westBefore = cur.sector(HARBOR_W).stock().get(FOOD);
        boolean loaded = false, unloaded = false;
        for (int i = 0; i < 8 && !unloaded; i++) {
            cur = Update.run(cur, CFG, 10 + i).next();
            Ship s = cur.ship(1);
            if (s.note().contains("loaded") && !s.note().contains("unloaded")) loaded = true;
            if (s.note().contains("unloaded")) unloaded = true;
        }
        assertThat(loaded).isTrue();
        assertThat(unloaded).isTrue();
        assertThat(cur.sector(HARBOR_W).stock().get(FOOD)).isGreaterThan(westBefore + 500);       // 600-hold cargo ship, harbour kept its 200 threshold
        assertThat(cur.ship(1).lane().outbound()).isFalse();                                         // heading back to load again
    }

    @Test
    void loadAndUnloadOnlyInYourHarbourWithinTheHold() {
        World w = withShip(world(), "cargo_ship", HARBOR_E, 100);
        CommandExecutor ex = new CommandExecutor(CFG);
        CommandResult l = ex.execute(w, 0, new Command.Load(1, "food", 1000));
        assertThat(l.error()).isNull();
        assertThat(l.world().ship(1).stock().get(FOOD)).isCloseTo(600, within(1e-9));               // hold 600
        assertThat(l.info()).contains("hold full");
        CommandResult u = ex.execute(l.world(), 0, new Command.Unload(1, "food", 100));
        assertThat(u.world().ship(1).stock().get(FOOD)).isCloseTo(500, within(1e-9));
        World atSea = w.withShip(w.ship(1).withAt(SEA_E));
        assertThat(ex.execute(atSea, 0, new Command.Load(1, "food", 10)).error()).contains("not in one of your harbours");
        assertThat(ex.execute(w, 0, new Command.Load(1, "civ", 10)).error()).isNull();              // cargo carries all
        World tanker = withShip(world(), "tanker", HARBOR_E, 100);
        assertThat(ex.execute(tanker, 0, new Command.Load(1, "food", 10)).error()).contains("cannot carry");
    }

    @Test
    void luxuryCraftMakePeopleHappyAtSea() {
        World w = withShip(world(), "luxury_craft", SEA_E, 100);
        UpdateResult u = Update.run(w, CFG, 1);
        assertThat(u.next().country(0).levels().happiness()).isGreaterThan(0);
        assertThat(u.next().ships().get(0).note()).contains("cruised");
        World docked = withShip(world(), "luxury_craft", HARBOR_E, 100);
        assertThat(Update.run(docked, CFG, 1).next().country(0).levels().happiness()).isCloseTo(0, within(1e-9));
    }

    @Test
    void aFishingMissionRoamsFishesLandsAndGoesOutAgain() {
        World w = withShip(world(), "fishing_boat", HARBOR_E, 100);
        for (int d = 0; d < 6; d++) for (int k = 1; k <= 3; k++) {
            Coord c = Hex.stepRaw(HARBOR_E, d, k);
            if (w.inBounds(c) && w.sector(c).terrain() == Terrain.OCEAN) w = w.withSector(w.sector(c).withTerrain(Terrain.OCEAN, 0, new Resources(40 + 10 * (d % 3), 0, 0, 0, 0)));
        }
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Fish(1, null, false));
        assertThat(r.error()).isNull();
        assertThat(r.world().ship(1).fishing()).isTrue();
        assertThat(r.world().ship(1).home()).isEqualTo(HARBOR_E);
        World cur = r.world();
        double foodBefore = cur.sector(HARBOR_E).stock().get(FOOD);
        int landings = 0, casts = 0, maxDist = 0;
        for (int i = 0; i < 12; i++) {
            cur = Update.run(cur, CFG, 20 + i).next();
            Ship s = cur.ship(1);
            maxDist = Math.max(maxDist, Hex.distanceRaw(s.at(), HARBOR_E));
            if (s.note().contains("fished")) casts++;
            if (s.note().contains("unloaded")) landings++;
        }
        assertThat(casts).isGreaterThan(2);
        assertThat(landings).isGreaterThanOrEqualTo(1);
        assertThat(maxDist).isLessThanOrEqualTo(CFG.units().ships().fishingOrDefault().radius());
        assertThat(cur.sector(HARBOR_E).stock().get(FOOD)).isGreaterThan(foodBefore);
        assertThat(cur.ship(1).fishing()).isTrue();
        assertThat(new CommandExecutor(CFG).execute(cur, 0, new Command.Sail(1, SEA_E)).world().ship(1).fishing()).isFalse();
        assertThat(new CommandExecutor(CFG).execute(cur, 0, new Command.Fish(1, null, true)).world().ship(1).fishing()).isFalse();
        World cargo = withShip(world(), "cargo_ship", HARBOR_E, 100);
        assertThat(new CommandExecutor(CFG).execute(cargo, 0, new Command.Fish(1, null, false)).error()).contains("does not fish");
    }

    @Test
    void theSeaIsFertileByRegion() {
        World w = new Sim(CFG).newWorld(List.of("P", "Q"), 5);
        long fertile = w.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN && s.resources().fertility() > 0).count();
        long ocean = w.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN).count();
        assertThat(fertile).isGreaterThan(ocean / 2);
        assertThat(w.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN).mapToInt(s -> s.resources().fertility()).distinct().count()).isGreaterThan(3);
    }
}
