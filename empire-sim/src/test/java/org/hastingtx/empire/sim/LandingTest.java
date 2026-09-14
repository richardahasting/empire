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

/**
 * Issue #193. Richard, 2026-09-14, wanting civilians on an island that touched nothing of his: "Then we
 * need assault ships, correct? Make it so they can take 100 mil and 20 civ."
 */
class LandingTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord HARBOR = Hex.stepRaw(CAP, 0, 2);
    private static final Coord ISLAND = new Coord(18, 11);          // a lone plain out at sea, nothing of ours next to it
    private static final Coord OFFSHORE = new Coord(17, 11);        // the sea hex beside it
    private static final CommandExecutor EX = new CommandExecutor(CFG);

    private static World world() {
        World w = TestWorlds.disc(CFG, 2, Map.of("civ", 500.0, "food", 400.0));
        w = w.withCountry(w.country(0).withCash(100000));
        w = TestWorlds.own(w, CFG, HARBOR, "harbor", 100, 127, Map.of("civ", 800.0, "mil", 500.0, "food", 300.0, "pet", 2000.0), Map.of());
        return w.withSector(w.sector(ISLAND).withTerrain(Terrain.PLAINS, 100, new Resources(80, 20, 0, 0, 0)));
    }

    private static World ship(World w, String cls, Coord at, double mil, double civ) {
        var c = CFG.units().ships().shipClass(cls);
        Ship s = new Ship(w.nextShipId(), 0, cls, "", at, 100, Stocks.zero(COM.size()).with(COM.mil, mil).with(COM.civ, civ), null, null, 0, "", 0, null, null, c.tankOr0(), c.crewOr0());
        List<Ship> ships = new ArrayList<>(w.ships());
        ships.add(s);
        return w.withShips(ships, s.id() + 1);
    }

    @Test
    void anAssaultShipTakesAHundredMilAndTwentyCiv() {
        World w = ship(world(), "assault_ship", HARBOR, 0, 0);
        CommandResult mil = EX.execute(w, 0, new Command.Load(1, "mil", 500));
        assertThat(mil.error()).as(mil.error()).isNull();
        CommandResult civ = EX.execute(mil.world(), 0, new Command.Load(1, "civ", 500));
        assertThat(civ.error()).as(civ.error()).isNull();
        assertThat(civ.world().ship(1).stock().get(COM.mil)).isEqualTo(100);
        assertThat(civ.world().ship(1).stock().get(COM.civ)).isEqualTo(20);
        assertThat(EX.execute(civ.world(), 0, new Command.Load(1, "civ", 5)).error()).contains("no more than 20 civ");
        assertThat(EX.execute(civ.world(), 0, new Command.Load(1, "food", 5)).error()).contains("cannot carry");
    }

    @Test
    void landingOnUnownedCoastMakesItYoursWithEveryoneAshore() {
        World w = ship(world(), "assault_ship", OFFSHORE, 100, 20);
        CommandResult r = EX.execute(w, 0, new Command.Land(1, ISLAND));
        assertThat(r.error()).as(r.error()).isNull();
        Sector s = r.world().sector(ISLAND);
        assertThat(s.owner()).isEqualTo(0);
        assertThat(s.stock().get(COM.mil)).isEqualTo(100);
        assertThat(s.stock().get(COM.civ)).isEqualTo(20);
        assertThat(r.world().ship(1).load()).isZero();
        assertThat(r.info()).contains("landed 100 mil and 20 civ").contains("the land feeds them");
        // and it is really held: it survives an update (conservation passes) and they explore on from it
        World after = Update.run(r.world(), CFG, 5).next();
        assertThat(after.sector(ISLAND).owner()).isEqualTo(0);
    }

    @Test
    void poorGroundIsWarnedAbout() {
        World w = world();
        w = w.withSector(w.sector(ISLAND).withTerrain(Terrain.MOUNTAIN, 100, new Resources(5, 60, 0, 0, 0)));
        CommandResult r = EX.execute(ship(w, "assault_ship", OFFSHORE, 100, 20), 0, new Command.Land(1, ISLAND));
        assertThat(r.info()).contains("WARNING").contains("starve");
    }

    @Test
    void theRefusals() {
        World w = ship(world(), "assault_ship", OFFSHORE, 100, 20);
        assertThat(EX.execute(w, 0, new Command.Land(1, new Coord(20, 11))).error()).contains("not next to");
        assertThat(EX.execute(w, 0, new Command.Land(1, Hex.stepRaw(OFFSHORE, 1, 1))).error()).contains("is sea");
        World mine = w.withSector(w.sector(ISLAND).withOwner(0));
        assertThat(EX.execute(mine, 0, new Command.Land(1, ISLAND)).error()).contains("already yours");
        List<Country> cs = new ArrayList<>(w.countries());
        cs.add(new Country(1, "Them", new Coord(2, 2), 1000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0));
        World theirs = w.withCountries(cs).withSector(w.sector(ISLAND).withOwner(1));
        assertThat(EX.execute(theirs, 0, new Command.Land(1, ISLAND)).error()).contains("belongs to Them");
        assertThat(EX.execute(ship(world(), "cargo_ship", OFFSHORE, 0, 20), 0, new Command.Land(1, ISLAND)).error()).contains("assault ship");
        assertThat(EX.execute(ship(world(), "assault_ship", OFFSHORE, 0, 0), 0, new Command.Land(1, ISLAND)).error()).contains("nobody aboard");
    }
}
