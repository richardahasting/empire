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
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        w = w.withCountry(w.country(0).withCash(100000));
        for (Coord h : new Coord[] {HARBOR_E, HARBOR_W})
            // petrol too: a ship does not leave port until her tank is full (2026-09-14), so a harbour with none keeps its fleet in
            w = TestWorlds.own(w, CFG, h, "harbor", 100, 127, Map.of("civ", 500.0, "food", 300.0, "lcm", 500.0, "hcm", 200.0, "pet", 2000.0), Map.of("food", 200.0));
        // fishing grounds east of the eastern harbour
        Sector sea = w.sector(SEA_E);
        w = w.withSector(sea.withTerrain(Terrain.OCEAN, 0, new Resources(60, 0, 0, 0, 0)));
        return w;
    }
    /** Set one sector's food, for tests that need a full or an empty harbour under the 1000 cap. */
    private static World stockFood(World w, Coord at, double food) {
        Sector s = w.sector(at);
        return w.withSector(s.withStock(s.stock().with(COM.food, food)));
    }

    private static World withShip(World w, String cls, Coord at, double eff) { return withShip(w, cls, at, eff, 0); }
    /**
     * A hull with a full tank and a full crew. These tests are about speed, lanes and fishing; fuel and
     * crews have their own tests (issues #65, #66), and a dry tank or an empty muster would hold every
     * ship here at the quay and prove nothing.
     */
    private static World withShip(World w, String cls, Coord at, double eff, double tech) {
        var c = CFG.units().ships().shipClass(cls);
        Ship s = new Ship(w.nextShipId(), 0, cls, "", at, eff, Stocks.zero(COM.size()), null, null, 0, "", tech, null, null, c.tankOr0(), c.crewOr0());
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
        // a lane moves surplus from a full harbour to an empty one; under the 1000 cap the
        // destination must have room for the hold (issue #77)
        World w = stockFood(stockFood(withShip(world(), "cargo_ship", HARBOR_E, 100), HARBOR_E, 800), HARBOR_W, 100);
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
        World w = stockFood(withShip(world(), "cargo_ship", HARBOR_E, 100), HARBOR_E, 800);
        CommandExecutor ex = new CommandExecutor(CFG);
        CommandResult l = ex.execute(w, 0, new Command.Load(1, "food", 1000));
        assertThat(l.error()).isNull();
        assertThat(l.world().ship(1).stock().get(FOOD)).isCloseTo(600, within(1e-9));               // hold 600
        assertThat(l.info()).contains("hold full");
        CommandResult u = ex.execute(l.world(), 0, new Command.Unload(1, "food", 100));
        assertThat(u.world().ship(1).stock().get(FOOD)).isCloseTo(500, within(1e-9));
        World atSea = w.withShip(w.ship(1).withAt(SEA_E));
        assertThat(ex.execute(atSea, 0, new Command.Load(1, "food", 10)).error()).contains("not in one of your harbours");
        assertThat(ex.execute(w, 0, new Command.Load(1, "civ", 10)).error()).contains("cannot carry");  // goods only: people go by ferry (issue #242)
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

    /**
     * The running manifest (issue #244, Richard 2026-09-15: "how much food, iron, happiness, or deliveries each ship has
     * created since the ship's creation"). Every fish is caught once, and either delivered or still aboard.
     */
    @Test
    void aShipKeepsARunningManifest() {
        World w = withShip(world(), "fishing_boat", HARBOR_E, 100);
        for (int d = 0; d < 6; d++) for (int k = 1; k <= 3; k++) {
            Coord c = Hex.stepRaw(HARBOR_E, d, k);
            if (w.inBounds(c) && w.sector(c).terrain() == Terrain.OCEAN) w = w.withSector(w.sector(c).withTerrain(Terrain.OCEAN, 0, new Resources(50, 0, 0, 0, 0)));
        }
        World cur = new CommandExecutor(CFG).execute(w, 0, new Command.Fish(1, null, false)).world();
        double fishedByNote = 0;
        var fished = java.util.regex.Pattern.compile("fished (\\d+(?:\\.\\d)?) food");
        for (int i = 0; i < 12; i++) {
            cur = Update.run(cur, CFG, 60 + i).next();
            var m = fished.matcher(cur.ship(1).note());
            while (m.find()) fishedByNote += Double.parseDouble(m.group(1));
        }
        Ship boat = cur.ship(1);
        double caught = boat.manifest().getOrDefault(Ship.CAUGHT + "food", 0.0), delivered = boat.manifest().getOrDefault(Ship.DELIVERED + "food", 0.0);
        assertThat(caught).as("she caught fish").isGreaterThan(0).isCloseTo(fishedByNote, within(fishedByNote * 0.02 + 1));
        assertThat(delivered).as("and landed some").isGreaterThan(0);
        assertThat(caught).as("every fish caught is delivered or still aboard").isCloseTo(delivered + boat.stock().get(FOOD), within(1e-6));

        // happiness from a cruise, and a delivery by hand
        World lux = Update.run(withShip(world(), "luxury_craft", SEA_E, 100), CFG, 1).next();
        assertThat(lux.ship(1).manifest().get(Ship.HAPPINESS)).isGreaterThan(0);
        World cargo = withShip(world(), "cargo_ship", HARBOR_E, 100);
        cargo = cargo.withShip(cargo.ship(1).withStock(cargo.ship(1).stock().plus(COM.index("lcm"), 50)));
        Ship unloaded = new CommandExecutor(CFG).execute(cargo, 0, new Command.Unload(1, "lcm", 30)).world().ship(1);
        assertThat(unloaded.manifest()).containsEntry(Ship.DELIVERED + "lcm", 30.0);
        assertThat(unloaded.tally(Ship.DELIVERED + "lcm", 5).manifest()).as("it runs on").containsEntry(Ship.DELIVERED + "lcm", 35.0);
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
        // a sail pauses the mission rather than ending it (issues #201, #205); only off ends it
        Ship sent = new CommandExecutor(CFG).execute(cur, 0, new Command.Sail(1, SEA_E)).world().ship(1);
        assertThat(sent.fishing()).isTrue();
        assertThat(sent.handLeg() || sent.at().equals(SEA_E)).isTrue();
        assertThat(new CommandExecutor(CFG).execute(cur, 0, new Command.Fish(1, null, true)).world().ship(1).fishing()).isFalse();
        World cargo = withShip(world(), "cargo_ship", HARBOR_E, 100);
        assertThat(new CommandExecutor(CFG).execute(cargo, 0, new Command.Fish(1, null, false)).error()).contains("does not fish");
    }

    @Test
    void aShipLiftsTheFogAroundIt() {
        World w = withShip(world(), "cargo_ship", Hex.stepRaw(CAP, 0, 6), 100);       // four hexes beyond the disc's rim
        org.hastingtx.empire.engine.view.CountryView v = org.hastingtx.empire.engine.view.CountryView.of(w, CFG, 0);
        Coord farSea = Hex.stepRaw(CAP, 0, 8);                                        // two hexes past the ship: in its sight of 2
        Coord beyond = Hex.stepRaw(CAP, 0, 9);
        assertThat(v.sectors()).anyMatch(s -> s.at().equals(farSea));
        assertThat(v.sectors()).noneMatch(s -> s.at().equals(beyond));
        org.hastingtx.empire.engine.view.CountryView none = org.hastingtx.empire.engine.view.CountryView.of(world(), CFG, 0);
        assertThat(none.sectors()).noneMatch(s -> s.at().equals(farSea));
    }

    @Test
    void theSeaIsFertileByRegion() {
        World w = new Sim(CFG).newWorld(List.of("P", "Q"), 5);
        long fertile = w.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN && s.resources().fertility() > 0).count();
        long ocean = w.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN).count();
        assertThat(fertile).isGreaterThan(ocean / 2);
        assertThat(w.sectors().stream().filter(s -> s.terrain() == Terrain.OCEAN).mapToInt(s -> s.resources().fertility()).distinct().count()).isGreaterThan(3);
    }

    // ---- issue #78: a harbour reaches the warehouse next door ----

    /** Own {@code at} as a warehouse of country {@code owner}, with the given stock. */
    private static World warehouse(World w, Coord at, int owner, double food) {
        Sector s = w.sector(at).withOwner(owner).withDesignation("warehouse", 100).withMobility(127);
        return w.withSector(s.withStock(s.stock().with(COM.food, food)));
    }

    @Test
    void aShipUnloadsPastAFullHarbourIntoTheWarehouseNextDoor() {
        Coord shed = Hex.stepRaw(HARBOR_E, 3, 1);                       // a land hex beside the harbour
        World w = warehouse(stockFood(withShip(world(), "cargo_ship", HARBOR_E, 100), HARBOR_E, 10000), shed, 0, 0);
        w = w.withShip(w.ship(1).withStock(w.ship(1).stock().with(COM.food, 500)));
        double shedBefore = w.sector(shed).stock().get(FOOD);

        World after = Update.run(new CommandExecutor(CFG).execute(w, 0, new Command.Unload(1, "food", 500)).world(), CFG, 1).next();
        assertThat(after.sector(shed).stock().get(FOOD)).as("spilled into the warehouse").isGreaterThan(shedBefore);
        assertThat(after.ship(1).stock().get(FOOD)).isLessThan(500);
    }

    @Test
    void aShipLoadsAHoldTheHarbourAloneCannotFill() {
        Coord shed = Hex.stepRaw(HARBOR_E, 3, 1);
        World w = warehouse(stockFood(withShip(world(), "cargo_ship", HARBOR_E, 100), HARBOR_E, 250), shed, 0, 900);
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Load(1, "food", 600));
        assertThat(r.error()).as(r.error()).isNull();
        assertThat(r.world().ship(1).stock().get(FOOD)).as("harbour plus warehouse fills the hold").isCloseTo(600, within(1e-9));
        assertThat(r.info()).contains("warehouse");
    }

    @Test
    void aShipNeverReachesANeighboursWarehouseNorOneTooFarAway() {
        Coord theirs = Hex.stepRaw(HARBOR_E, 3, 1);
        Coord distant = Hex.stepRaw(HARBOR_E, 3, 3);                    // owned by us, but not adjacent
        World w = warehouse(warehouse(stockFood(withShip(world(), "cargo_ship", HARBOR_E, 100), HARBOR_E, 250), theirs, 1, 900), distant, 0, 900);
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Load(1, "food", 600));
        // only the harbour's own 250 above a 200 threshold is reachable: not a foreign shed, not a distant one
        assertThat(r.world().ship(1).stock().get(FOOD)).isCloseTo(250, within(1e-9));
    }

    /**
     * Game 82, 2026-09-14: boats sent into a nearer harbour for fuel sat there for ever saying "no fishing
     * grounds within 6", because from a strange harbour they look for water a leg away that is also near
     * home, and there was none. With nowhere to work from where she is, she goes home and works from there.
     */
    @Test
    void aBoatInAStrangeHarbourGoesHomeToFish() {
        World w = world();
        Coord away = new Coord(22, 11);                          // nine hexes from HARBOR_E: nothing a leg away is near home
        w = TestWorlds.own(w, CFG, away, "harbor", 100, 127, Map.of("civ", 500.0, "food", 300.0, "pet", 2000.0), Map.of());
        for (int d = 0; d < 6; d++) for (int k = 1; k <= 3; k++) {
            Coord c = Hex.stepRaw(HARBOR_E, d, k);
            if (w.inBounds(c) && w.sector(c).terrain() == Terrain.OCEAN) w = w.withSector(w.sector(c).withTerrain(Terrain.OCEAN, 0, new Resources(50, 0, 0, 0, 0)));
        }
        w = withShip(w, "fishing_boat", away, 100);
        w = w.withShip(w.ship(1).withMission(Ship.FISH, HARBOR_E));
        boolean headedHome = false, fished = false;
        for (int i = 0; i < 12 && !fished; i++) {
            w = Update.run(w, CFG, 90 + i).next();
            Ship s = w.ship(1);
            if (s.note().contains("heading home")) headedHome = true;
            if (headedHome && s.note().contains("fished")) fished = true;
            assertThat(s.note()).as("update %d", i).doesNotContain("no fishing grounds within");
        }
        assertThat(headedHome).as("she set off for home").isTrue();
        assertThat(fished).as("and was fishing her own grounds again").isTrue();
    }
}
