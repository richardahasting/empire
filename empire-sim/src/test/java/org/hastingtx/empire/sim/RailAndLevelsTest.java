package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Levels;
import org.hastingtx.empire.engine.model.Resources;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.Terrain;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RailAndLevelsTest {
    static final int IRON = 4, LCM = 11;

    /** Two depots five sectors apart joined by rail: the train is checked at issue and arrives at the update. */
    @Test
    void trainRunsDepotToDepot() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 7, Map.of("civ", 500.0, "food", 800.0, "iron", 1000.0, "lcm", 500.0));
        w = w.withCountry(w.country(0).withLevels(new Levels(70, 0, 0, 0)));            // rail needs tech 60
        Coord a = TestWorlds.CENTER, b = Hex.stepRaw(a, 0, 5);
        w = w.withSector(w.sector(a).withDesignation("depot", 100).withRailLevel(100));
        for (int k = 1; k <= 5; k++) {
            Coord c = Hex.stepRaw(a, 0, k);
            w = TestWorlds.own(w, cfg, c, k == 5 ? "depot" : "agribusiness", 100, 60, Map.of("civ", 100.0, "food", 300.0), Map.of());
            w = w.withSector(w.sector(c).withRailLevel(100));
        }
        CommandExecutor exec = new CommandExecutor(cfg);
        CommandResult r = exec.execute(w, 0, new Command.RailShip(a, b, "iron", 800));
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.info()).contains("train scheduled");
        UpdateResult u = Update.run(r.world(), cfg, 5);
        assertThat(u.next().sector(b).stock().get(IRON)).as("iron arrived by rail (range 8 >= 5)").isGreaterThan(750);
        assertThat(u.next().sector(a).stock().get(IRON)).isLessThan(1000 - 750);
        assertThat(u.flows()).anyMatch(f -> f.kind().equals("rail") && f.completed());
        assertThat(u.next().country(0).cash()).as("cost by volume").isLessThan(w.country(0).cash() + 100000);
        // mobility (Richard 2026-09-09): rail is a fifth of road — 2000 iron × 0.1 (warehouse packing) × 5 plains hops × 0.2 = 200 by road, 40 over rail-100 hexes
        double moved = u.flows().stream().filter(f -> f.kind().equals("rail")).mapToDouble(f -> f.qtyMoved()).sum();
        double roadCost = moved * 0.1 * 5 * 0.2, railCost = roadCost * 0.2;
        assertThat(u.next().sector(a).mobility()).as("depot mobility after the train left").isCloseTo(127 - cfg.infrastructure().rail().mobilityMultiplier() * railCost, within(1e-6));
        assertThat(cfg.infrastructure().rail().mobilityMultiplier() * 0.2).as("trains pay a fifth of road").isCloseTo(0.2, within(1e-9));
        assertThat(u.notes().get(a.x() + "," + a.y())).anyMatch(l -> l.startsWith("train:"));
    }

    /** Issue #60: a rail order on the sea next to your land is a bridge, paid automatically by the adjacent sector with the most rail. */
    @Test
    void aBridgeIsPaidAutomaticallyAndCarriesTrains() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 7, Map.of("civ", 500.0, "food", 5000.0, "iron", 3000.0, "lcm", 500.0, "hcm", 500.0));
        w = w.withCountry(w.country(0).withLevels(new Levels(95, 0, 0, 0)));            // bridge needs tech 90
        Coord a = TestWorlds.CENTER, b = Hex.stepRaw(a, 0, 5), water = Hex.stepRaw(a, 0, 3);
        w = w.withSector(w.sector(a).withDesignation("depot", 100).withRailLevel(100));
        for (int k = 1; k <= 5; k++) {
            Coord c = Hex.stepRaw(a, 0, k);
            w = TestWorlds.own(w, cfg, c, k == 5 ? "depot" : "agribusiness", 100, 127, Map.of("civ", 100.0, "food", 300.0, "lcm", 400.0, "hcm", 400.0), Map.of());
            w = w.withSector(w.sector(c).withRailLevel(100));
        }
        Sector sea = w.sector(water);
        w = w.withSector(sea.withOwner(Sector.NOBODY).withDesignation("wilderness", 0).withRailLevel(0).withTerrain(Terrain.OCEAN, 0, new Resources(20, 0, 0, 0, 0)));   // a strait cuts the line
        CommandExecutor exec = new CommandExecutor(cfg);
        assertThat(exec.execute(w, 0, new Command.RailShip(a, b, "iron", 100)).error()).contains("no rail line");
        CommandResult order = exec.execute(w, 0, new Command.BuildRail(water, 100));
        assertThat(order.error()).as(order.error()).isNull();
        assertThat(order.info()).contains("bridge").contains("hcm");
        Coord sponsorAt = Hex.stepRaw(a, 0, 2);   // both banks have rail 100; the lower index sponsors — either way one of them pays
        double hcmBefore = order.world().sector(sponsorAt).stock().get(12) + order.world().sector(Hex.stepRaw(a, 0, 4)).stock().get(12);
        World cur = order.world();
        for (int i = 0; i < 4; i++) cur = Update.run(cur, cfg, 30 + i).next();          // 5 points an update: 20 after four
        assertThat(cur.sector(water).railLevel()).isCloseTo(20, within(1e-6));
        double hcmAfter = cur.sector(sponsorAt).stock().get(12) + cur.sector(Hex.stepRaw(a, 0, 4)).stock().get(12);
        assertThat(hcmBefore - hcmAfter).as("the bridge's 200 hcm plus 20 points × 1 hcm × 3.0 ocean multiplier").isCloseTo(200 + 60, within(1e-6));
        CommandResult ship = exec.execute(cur, 0, new Command.RailShip(a, b, "iron", 100));
        assertThat(ship.error()).as(ship.error()).isNull();
        assertThat(Update.run(ship.world(), cfg, 40).next().sector(b).stock().get(IRON)).isGreaterThan(50);
        // no tech, no bridge; open sea with no shore of yours, no bridge
        World lowTech = w.withCountry(w.country(0).withLevels(new Levels(70, 0, 0, 0)));
        assertThat(exec.execute(lowTech, 0, new Command.BuildRail(water, 100)).error()).contains("bridge needs tech");
        assertThat(exec.execute(w, 0, new Command.BuildRail(Hex.stepRaw(a, 0, 9), 100)).error()).contains("none of your land beside it");
    }

    /** Richard 2026-09-09: rail is a road that is cheaper still — its level discounts everything entering the sector. */
    @Test
    void railDiscountsEveryMoveIntoTheSector() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 2, Map.of("civ", 500.0, "food", 5000.0, "iron", 3000.0));
        Coord c = Hex.stepRaw(TestWorlds.CENTER, 0, 1);
        w = TestWorlds.own(w, cfg, c, "agribusiness", 100, 127, Map.of("civ", 100.0, "food", 300.0), Map.of());
        org.hastingtx.empire.engine.update.Ctx plain = new org.hastingtx.empire.engine.update.Ctx(w, cfg, org.hastingtx.empire.engine.model.Commodities.of(cfg), 0);
        double before = plain.moveCostInto(w.sector(c));
        World railed = w.withSector(w.sector(c).withRailLevel(100));
        org.hastingtx.empire.engine.update.Ctx withRail = new org.hastingtx.empire.engine.update.Ctx(railed, cfg, org.hastingtx.empire.engine.model.Commodities.of(cfg), 0);
        assertThat(withRail.moveCostInto(railed.sector(c))).isCloseTo(before * 0.2, within(1e-9));
    }

    /** A gap in the line is refused at issue time and names where the track ends. */
    @Test
    void severedLineIsRefusedWithTheBreakNamed() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 7, Map.of("civ", 500.0, "food", 5000.0, "iron", 3000.0));
        w = w.withCountry(w.country(0).withLevels(new Levels(70, 0, 0, 0)));
        Coord a = TestWorlds.CENTER, b = Hex.stepRaw(a, 0, 5);
        w = w.withSector(w.sector(a).withDesignation("depot", 100).withRailLevel(100));
        for (int k = 1; k <= 5; k++) {
            Coord c = Hex.stepRaw(a, 0, k);
            w = TestWorlds.own(w, cfg, c, k == 5 ? "depot" : "agribusiness", 100, 60, Map.of("civ", 100.0, "food", 300.0), Map.of());
            w = w.withSector(w.sector(c).withRailLevel(k == 3 ? 5 : 100));   // sector 3 has track below the carrying level
        }
        CommandResult r = new CommandExecutor(cfg).execute(w, 0, new Command.RailShip(a, b, "iron", 100));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("track ends at " + Hex.stepRaw(a, 0, 2));
    }

    /** Beyond the per-update range the train holds on the line and finishes next update; cutting the line strands it. */
    @Test
    void trainHoldsBeyondRangeAndStrandsWhenCut() {
        GameConfig cfg = TestWorlds.teaching();
        World w = TestWorlds.disc(cfg, 10, Map.of("civ", 500.0, "food", 900.0, "iron", 1000.0));
        // issue #79: distance is what mobility pays for. Poor track (rail 25) with a heavy load costs
        // enough per hop that the train cannot finish the 9-hop line in one update. Setting a low
        // starting mobility would not do it -- mobility accrues before the flow step and tops the
        // sector back up, so the price of a hop is the only real lever.
        Coord a = TestWorlds.CENTER;
        w = w.withSector(w.sector(a).withDesignation("depot", 100).withRailLevel(25));
        Coord cur = a;
        java.util.List<Coord> line = new java.util.ArrayList<>();
        for (int k = 1; k <= 5; k++) { cur = Hex.stepRaw(cur, 3); line.add(cur); }
        for (int k = 1; k <= 4; k++) { cur = Hex.stepRaw(cur, 4); line.add(cur); }
        Coord dest = cur;
        for (Coord c : line) {
            boolean last = c.equals(dest);
            w = TestWorlds.own(w, cfg, c, last ? "depot" : "agribusiness", 100, 60, Map.of("civ", 100.0, "food", 300.0), Map.of());
            w = w.withSector(w.sector(c).withRailLevel(25));
        }
        CommandResult r = new CommandExecutor(cfg).execute(w, 0, new Command.RailShip(a, dest, "iron", 900));
        assertThat(r.ok()).as(r.error()).isTrue();
        UpdateResult u1 = Update.run(r.world(), cfg, 8);
        // wherever its mobility ran out — assert the behaviour, not a hop number
        Coord holdAt = null;
        for (Coord c : line) if (!u1.next().sector(c).held().isEmpty()) holdAt = c;
        assertThat(holdAt).as("the train stopped somewhere on the line").isNotNull();
        assertThat(holdAt).as("it did not reach the far depot").isNotEqualTo(dest);
        assertThat(u1.next().sector(holdAt).held()).as("train holds on the line where mobility ran out").anyMatch(p -> p.rail() && p.qty() > 400);
        assertThat(u1.next().sector(dest).stock().get(IRON)).isZero();
        // cut the line ahead of the train
        Coord ahead = line.get(line.indexOf(holdAt) + 1);
        World cut = u1.next().withSector(u1.next().sector(ahead).withRailLevel(0));
        UpdateResult u2 = Update.run(cut, cfg, 9);
        assertThat(u2.events()).anyMatch(e -> e.type().equals("rail_stranded"));
        assertThat(u2.next().sector(holdAt).held()).as("still parked, not destroyed").anyMatch(p -> p.rail() && p.qty() > 400);
        // repair it: the train finishes
        World repaired = u2.next().withSector(u2.next().sector(ahead).withRailLevel(25));
        UpdateResult u3 = Update.run(repaired, cfg, 10);
        assertThat(u3.next().sector(dest).stock().get(IRON)).isGreaterThan(400);
    }

    /** Levels behave like the original: tech gains are log-limited above easy, education is a moving average, research ages. */
    @Test
    void levelsFollowTheOriginalFormulas() {
        GameConfig cfg = TestWorlds.teaching();
        EconomyCfg.LevelsCfg lc = cfg.economy().levels();
        assertThat(EconomyCfg.LevelsCfg.limit(0.5, 1.0, 2.0, false)).isEqualTo(0.5);
        assertThat(EconomyCfg.LevelsCfg.limit(9.0, 1.0, 2.0, false)).isCloseTo(1.0 + Math.log(9.0) / Math.log(2.0), within(1e-9));   // easy + log2((9 − 1) + 1)
        World w = TestWorlds.disc(cfg, 2, Map.of("civ", 500.0, "food", 5000.0, "lcm", 5000.0));
        w = w.withCountry(w.country(0).withLevels(new Levels(50, 30, 0, 0)));
        Coord school = Hex.stepRaw(TestWorlds.CENTER, 0, 1);
        w = TestWorlds.own(w, cfg, school, "school", 100, 60, Map.of("civ", 500.0, "food", 500.0, "lcm", 2000.0), Map.of());
        World after = w;
        for (int u = 0; u < 5; u++) after = Update.run(after, cfg, 20 + u).next();
        assertThat(after.country(0).levels().education()).as("education rises toward the rate the school sustains").isGreaterThan(0);
        assertThat(after.country(0).levels().research()).as("research aged 1% per 96 ETUs, no lab").isLessThan(30).isGreaterThan(30 * Math.pow(1 - 60.0 / 9600, 5) - 1e-6);
        assertThat(after.country(0).levels().tech()).as("tech aged too, nothing produced").isLessThan(50);
        assertThat(lc.levelAgeRate()).isEqualTo(96);
    }

    /**
     * Issue #79: distance is what mobility pays for, and rail level sets the price of a hop.
     * PROBE, not an invariant — if rail level stopped affecting reach this passes every conservation
     * check and the feature is silently dead, so assert the good line actually carries further.
     */
    @Test
    void aTrainOnGoodTrackRunsFurtherThanOneOnPoorTrack() {
        assertThat(hopsReached(100)).as("rail 100 outruns rail 25")
                .isGreaterThan(hopsReached(25));
    }

    /** Send a train down a 10-sector line built at {@code railLevel} and report how far it got in one update. */
    private static int hopsReached(double railLevel) {
        GameConfig cfg = TestWorlds.teaching();
        Commodities com = Commodities.of(cfg);
        World w = TestWorlds.disc(cfg, 12, Map.of("civ", 500.0, "food", 800.0, "iron", 1000.0, "lcm", 500.0));
        w = w.withCountry(w.country(0).withLevels(new Levels(70, 0, 0, 0)));
        Coord a = TestWorlds.CENTER, b = Hex.stepRaw(a, 0, 10);
        w = w.withSector(w.sector(a).withDesignation("depot", 100).withRailLevel(railLevel).withMobility(127));
        for (int k = 1; k <= 10; k++) {
            Coord c = Hex.stepRaw(a, 0, k);
            w = TestWorlds.own(w, cfg, c, k == 10 ? "depot" : "agribusiness", 100, 127, Map.of("civ", 100.0, "food", 100.0), Map.of());
            w = w.withSector(w.sector(c).withRailLevel(railLevel));
        }
        CommandResult r = new CommandExecutor(cfg).execute(w, 0, new Command.RailShip(a, b, "iron", 900));
        assertThat(r.ok()).as(r.error()).isTrue();
        World n = Update.run(r.world(), cfg, 5).next();
        // the train is either a held parcel somewhere down the line, or it arrived
        int furthest = 0;
        for (int k = 1; k <= 10; k++) {
            Coord c = Hex.stepRaw(a, 0, k);
            if (!n.sector(c).held().isEmpty()) furthest = Math.max(furthest, k);
            if (n.sector(c).stock().get(com.index("iron")) > 1 && k == 10) furthest = 10;
        }
        return furthest;
    }
}
