package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
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

/**
 * Issue #68: sea combat. War makes engagement automatic; peace does not; a shot fired in peacetime is
 * answered at once and marks the ship that fired it; submarines need finding and the right hull; the
 * coast shoots back; a harbour rearms; the victor salvages. Every update here also passes the apply
 * step's conservation check, which is what proves a sinking tallies what it takes out of the world.
 */
class CombatTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int FOOD = COM.food, GUN = COM.index("gun"), SHELL = COM.index("shell");
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord RIM_E = Hex.stepRaw(CAP, 0, 2);
    private static final Coord SEA = Hex.stepRaw(CAP, 0, 5), SEA_NEAR = Hex.stepRaw(CAP, 0, 6), SEA_FAR = Hex.stepRaw(CAP, 0, 9);
    private static final Coord THEIR_CAP = new Coord(2, 2);

    /** Us at the centre, them on a one-sector island in the corner, and at war or not. */
    private static World world(boolean war) {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        Sector theirs = w.sector(THEIR_CAP).withTerrain(Terrain.PLAINS, 100, new Resources(80, 40, 10, 10, 5))
                .withOwner(1).withDesignation("capital", 100).withMobility(127).withStock(Stocks.of(COM.fromMap(Map.of("civ", 500.0, "food", 400.0))));
        w = w.withSector(theirs);
        List<Country> countries = new ArrayList<>(w.countries());
        countries.set(0, w.country(0).withCash(100000));
        countries.add(new Country(1, "Them", THEIR_CAP, 100000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        w = w.withCountries(countries);
        if (war) w = w.withRelations(List.of(Relation.of(0, 1, Relation.WAR, 0, null)));
        return w;
    }

    /** A hull with a full tank, a full crew, and — if armed — her guns and a full magazine. */
    private static World ship(World w, int owner, String cls, Coord at) {
        var c = CFG.units().ships().shipClass(cls);
        Stocks st = Stocks.zero(COM.size());
        if (c.armed()) st = st.with(GUN, c.gunsOr0()).with(SHELL, c.magazineOr0());
        Ship s = new Ship(w.nextShipId(), owner, cls, "", at, 100, st, null, null, 0, "", 0, null, null, c.tankOr0(), c.crewOr0());
        List<Ship> ships = new ArrayList<>(w.ships());
        ships.add(s);
        return w.withShips(ships, s.id() + 1);
    }

    private static World withCargo(World w, long id, int c, double q) { Ship s = w.ship(id); return w.withShip(s.withStock(s.stock().plus(c, q))); }

    private static World seen(World w, int by, long shipId) {
        Ship t = w.ship(shipId);
        List<Contact> cs = new ArrayList<>(w.contacts());
        cs.add(new Contact(by, shipId, t.owner(), t.cls(), t.at(), w.updateNumber(), 1.0));
        return w.withContacts(cs);
    }

    // ---- the point of it: war against peace ----

    @Test
    void atWarADestroyerSinksAMerchantmanAndHerCargoIsSalvaged() {
        World w = ship(ship(ship(world(true), 0, "destroyer", SEA), 0, "cargo_ship", SEA_NEAR), 1, "cargo_ship", SEA_NEAR);
        w = withCargo(w, 3, FOOD, 400);                      // their freighter, loaded
        boolean sunk = false;
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 10 && !sunk; i++) {
            UpdateResult u = Update.run(w, CFG, 100 + i);
            u.events().forEach(e -> events.add(e.type()));
            w = u.next();
            sunk = w.ship(3) == null;
        }
        assertThat(sunk).as("their freighter went down").isTrue();
        assertThat(events).contains("ship_sunk");
        assertThat(w.ship(2).stock().get(FOOD)).as("our freighter in her hex salvaged three quarters").isEqualTo(300);
        assertThat(w.ship(1).stock().get(SHELL)).as("shells were spent").isLessThan(60);
    }

    @Test
    void atPeaceNothingFiresByItself() {
        World w = ship(ship(world(false), 0, "destroyer", SEA), 1, "destroyer", SEA_NEAR);
        for (int i = 0; i < 4; i++) w = Update.run(w, CFG, 200 + i).next();
        assertThat(w.ship(1).stock().get(SHELL)).isEqualTo(60);
        assertThat(w.ship(2).stock().get(SHELL)).isEqualTo(60);
        assertThat(w.ship(2).efficiency()).as("only the sea wore her").isGreaterThan(80);
    }

    // ---- fire: now, answered, and remembered ----

    @Test
    void firingInPeacetimeIsAnsweredAtOnceAndMarksTheShipThatFired() {
        World w = seen(ship(ship(world(false), 0, "destroyer", SEA), 1, "destroyer", SEA_NEAR), 0, 2);
        CommandResult r = new CommandExecutor(CFG).execute(w, 0, new Command.Fire(1, SEA_NEAR, null));
        assertThat(r.error()).as(r.error()).isNull();
        World n = r.world();
        assertThat(n.ship(2).efficiency()).as("hit").isLessThan(100);
        assertThat(n.ship(1).efficiency()).as("answered in the same exchange").isLessThan(100);
        assertThat(n.ship(1).stock().get(SHELL)).isEqualTo(50);
        assertThat(n.ship(1).firedOnBy(1, n.updateNumber() + 1)).isTrue();
        assertThat(n.atWar(0, 1)).as("no war was declared").isFalse();
        assertThat(n.contacts()).anyMatch(c -> c.owner() == 1 && c.shipId() == 1);
        assertThat(r.info()).contains("answered").contains("not at war");

        // at the update, they may shoot her; she may not shoot them, because nothing marks their ship
        World after = Update.run(n, CFG, 300).next();
        assertThat(after.ship(2).stock().get(SHELL)).as("they engaged the ship that fired").isLessThan(n.ship(2).stock().get(SHELL));
        assertThat(after.ship(1).stock().get(SHELL)).as("she did not start another fight").isEqualTo(n.ship(1).stock().get(SHELL));

        // and the mark runs out
        World later = after;
        for (int i = 0; i < CFG.units().ships().combat().grudgeUpdates() + 1; i++) later = Update.run(later, CFG, 310 + i).next();
        if (later.ship(1) != null) assertThat(later.ship(1).firedOn()).isEmpty();
    }

    @Test
    void youCannotFireAtWhatYouCannotSee() {
        World w = ship(ship(world(true), 0, "destroyer", SEA), 1, "cargo_ship", SEA_NEAR);
        World far = ship(ship(world(true), 0, "battleship", SEA), 1, "cargo_ship", SEA_FAR);               // 4 away: in a battleship's range, beyond her sight
        CommandResult blind = new CommandExecutor(CFG).execute(far, 0, new Command.Fire(1, SEA_FAR, null));
        assertThat(blind.error()).contains("can see");
        CommandResult aimed = new CommandExecutor(CFG).execute(seen(far, 0, 2), 0, new Command.Fire(1, SEA_FAR, null));
        assertThat(aimed.error()).as(aimed.error()).isNull();
        CommandResult outOfRange = new CommandExecutor(CFG).execute(seen(ship(ship(world(true), 0, "destroyer", SEA), 1, "cargo_ship", SEA_FAR), 0, 2), 0, new Command.Fire(1, SEA_FAR, null));
        assertThat(outOfRange.error()).contains("reaches");
        assertThat(new CommandExecutor(CFG).execute(w, 0, new Command.Fire(2, SEA, null)).error()).contains("no ship #2 of yours");
    }

    // ---- submarines ----

    @Test
    void aSubmarineIsHitOnlyOnceFoundAndOnlyByAHullThatHuntsThem() {
        World base = ship(world(true), 1, "submarine", SEA_NEAR);
        World battleship = seen(ship(base, 0, "battleship", SEA), 0, 1);
        World b1 = Update.run(battleship, CFG, 400).next();
        assertThat(b1.ship(1).efficiency()).as("a battleship cannot touch her").isGreaterThan(90);

        World destroyer = seen(ship(base, 0, "destroyer", SEA), 0, 1);
        World d1 = Update.run(destroyer, CFG, 401).next();
        assertThat(d1.ship(1) == null || d1.ship(1).efficiency() < 90).as("a destroyer with a contact hits her").isTrue();

        assertThat(new CommandExecutor(CFG).execute(battleship, 0, new Command.Fire(2, SEA_NEAR, null)).error()).contains("submarine");
    }

    // ---- the coast ----

    @Test
    void aFortOnTheCoastFiresOnAnEnemyAtWar() {
        World w = TestWorlds.own(world(true), CFG, RIM_E, "fortress", 100, 127, Map.of("mil", 200.0, "gun", 20.0, "shell", 100.0), Map.of());
        Coord offshore = Hex.stepRaw(CAP, 0, 4);                 // two hexes off the fort
        w = ship(w, 1, "cargo_ship", offshore);
        UpdateResult u = Update.run(w, CFG, 500);
        assertThat(u.next().ship(1) == null || u.next().ship(1).efficiency() < 97).isTrue();
        assertThat(u.next().sector(RIM_E).stock().get(SHELL)).isLessThan(100);
        assertThat(u.notes().get(RIM_E.x() + "," + RIM_E.y())).anyMatch(l -> l.startsWith("coastal guns fired"));

        World peace = ship(TestWorlds.own(world(false), CFG, RIM_E, "fortress", 100, 127, Map.of("mil", 200.0, "gun", 20.0, "shell", 100.0), Map.of()), 1, "cargo_ship", offshore);
        assertThat(Update.run(peace, CFG, 501).next().sector(RIM_E).stock().get(SHELL)).isEqualTo(100);
    }

    // ---- the yard ----

    @Test
    void aHarbourRearmsAWarshipAndANewOneLeavesTheYardArmed() {
        World w = TestWorlds.own(world(false), CFG, RIM_E, "harbor", 100, 127, Map.of("civ", 500.0, "mil", 500.0, "gun", 50.0, "shell", 500.0, "lcm", 500.0, "hcm", 500.0, "oil", 500.0, "food", 300.0), Map.of());
        w = ship(w, 0, "destroyer", RIM_E);
        w = w.withShip(w.ship(1).withStock(Stocks.zero(COM.size())));
        World n = Update.run(w, CFG, 600).next();
        assertThat(n.ship(1).stock().get(GUN)).isEqualTo(10);
        assertThat(n.ship(1).stock().get(SHELL)).isEqualTo(60);

        World techy = w.withCountry(w.country(0).withLevels(new Levels(50, 0, 0, 0)));
        CommandResult built = new CommandExecutor(CFG).execute(techy, 0, new Command.BuildShip(RIM_E, "destroyer", null));
        assertThat(built.error()).as(built.error()).isNull();
        Ship fresh = built.world().ships().get(built.world().ships().size() - 1);
        assertThat(fresh.stock().get(GUN)).isEqualTo(10);
        assertThat(fresh.stock().get(SHELL)).isEqualTo(50);
        assertThat(new CommandExecutor(CFG).execute(n, 0, new Command.Supply(1, null, false)).error()).containsAnyOf("does not run cargo", "carries no cargo");
    }

    // ---- a game from before combat ----

    @Test
    @SuppressWarnings("unchecked")
    void aGameWhoseRulesPredateCombatStillLoadsAndNothingFires() {
        ConfigLoader loader = new ConfigLoader();
        Map<String, Object> raw = loader.loadPreset("teaching").raw();
        Map<String, Object> ships = (Map<String, Object>) ((Map<String, Object>) raw.get("units")).get("ships");
        ships.remove("combat");
        for (Object o : (List<Object>) ships.get("classes"))
            for (String k : List.of("guns", "range", "armor", "magazine", "asw", "hit_per_gun")) ((Map<String, Object>) o).remove(k);
        GameConfig old = loader.loadYaml(loader.toYaml(raw)).config();
        assertThat(old.units().ships().combat()).isNull();

        World w = ship(ship(world(true), 0, "cargo_ship", SEA), 1, "cargo_ship", SEA_NEAR);
        w = w.withShip(w.ship(1).withStock(w.ship(1).stock().with(GUN, 10).with(SHELL, 60)));
        World n = Update.run(w, old, 700).next();
        assertThat(n.ship(2).efficiency()).isGreaterThan(90);
        assertThat(new CommandExecutor(old).execute(w, 0, new Command.Fire(1, SEA_NEAR, null)).error()).contains("no rules for combat");
    }
}
