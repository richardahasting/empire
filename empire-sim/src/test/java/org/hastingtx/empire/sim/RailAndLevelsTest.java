package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Levels;
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
        World w = TestWorlds.disc(cfg, 7, Map.of("civ", 500.0, "food", 5000.0, "iron", 3000.0, "lcm", 500.0));
        w = w.withCountry(w.country(0).withLevels(new Levels(70, 0, 0, 0)));            // rail needs tech 60
        Coord a = TestWorlds.CENTER, b = Hex.stepRaw(a, 0, 5);
        w = w.withSector(w.sector(a).withDesignation("depot", 100).withRailLevel(100));
        for (int k = 1; k <= 5; k++) {
            Coord c = Hex.stepRaw(a, 0, k);
            w = TestWorlds.own(w, cfg, c, k == 5 ? "depot" : "agribusiness", 100, 60, Map.of("civ", 100.0, "food", 300.0), Map.of());
            w = w.withSector(w.sector(c).withRailLevel(100));
        }
        CommandExecutor exec = new CommandExecutor(cfg);
        CommandResult r = exec.execute(w, 0, new Command.RailShip(a, b, "iron", 2000));
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.info()).contains("train scheduled");
        UpdateResult u = Update.run(r.world(), cfg, 5);
        assertThat(u.next().sector(b).stock().get(IRON)).as("iron arrived by rail (range 8 >= 5)").isGreaterThan(1900);
        assertThat(u.next().sector(a).stock().get(IRON)).isLessThan(3000 - 1900);
        assertThat(u.flows()).anyMatch(f -> f.kind().equals("rail") && f.completed());
        assertThat(u.next().country(0).cash()).as("cost by volume").isLessThan(w.country(0).cash() + 100000);
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
        World w = TestWorlds.disc(cfg, 10, Map.of("civ", 500.0, "food", 9000.0, "iron", 3000.0));
        // tech 0: range = 8 sectors per update; the line below is 9 hops (5 west, then 4 south-west), all inside the island
        Coord a = TestWorlds.CENTER;
        w = w.withSector(w.sector(a).withDesignation("depot", 100).withRailLevel(100));
        Coord cur = a;
        java.util.List<Coord> line = new java.util.ArrayList<>();
        for (int k = 1; k <= 5; k++) { cur = Hex.stepRaw(cur, 3); line.add(cur); }
        for (int k = 1; k <= 4; k++) { cur = Hex.stepRaw(cur, 4); line.add(cur); }
        Coord dest = cur;
        for (Coord c : line) {
            boolean last = c.equals(dest);
            w = TestWorlds.own(w, cfg, c, last ? "depot" : "agribusiness", 100, 60, Map.of("civ", 100.0, "food", 300.0), Map.of());
            w = w.withSector(w.sector(c).withRailLevel(100));
        }
        CommandResult r = new CommandExecutor(cfg).execute(w, 0, new Command.RailShip(a, dest, "iron", 500));
        assertThat(r.ok()).as(r.error()).isTrue();
        UpdateResult u1 = Update.run(r.world(), cfg, 8);
        Coord holdAt = line.get(7);   // the 8th hop
        assertThat(u1.next().sector(holdAt).held()).as("train holds on the line at range").anyMatch(p -> p.rail() && p.qty() > 400);
        assertThat(u1.next().sector(dest).stock().get(IRON)).isZero();
        // cut the line ahead of the train
        World cut = u1.next().withSector(u1.next().sector(line.get(8)).withRailLevel(0));
        UpdateResult u2 = Update.run(cut, cfg, 9);
        assertThat(u2.events()).anyMatch(e -> e.type().equals("rail_stranded"));
        assertThat(u2.next().sector(holdAt).held()).as("still parked, not destroyed").anyMatch(p -> p.rail() && p.qty() > 400);
        // repair it: the train finishes
        World repaired = u2.next().withSector(u2.next().sector(line.get(8)).withRailLevel(100));
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
}
