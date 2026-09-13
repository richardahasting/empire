package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Playtest game 82 (issues #147, #148, #151). One problem wearing three hats: the only way to ask the
 * game a question was to change it. A delivery toward the fog was "noted" and then kept as a dead
 * pipe; a second delivery silently destroyed the first; and exploring landed people on ground that
 * starved them by the next update.
 */
class ProbeWithoutWritingTest {

    private static final GameConfig CFG = new ConfigLoader().loadPreset("sandbox").config();
    private static final Commodities COM = Commodities.of(CFG);

    /** A country that has grown for a while, so it has a coast, several neighbours and a frontier. */
    private static World world() {
        Sim sim = new Sim(CFG);
        World w = sim.newWorld(List.of("A"), 11L);
        return sim.run(w, 12, 11L, id -> new org.hastingtx.empire.agents.scripted.ScriptedAgent()).world();
    }

    private static CommandResult run(World w, Command c) { return new CommandExecutor(CFG).execute(w, 0, c); }

    /** {@code from} is an owned land sector and {@code dir} points at something that is not ours. */
    private record Edge(Coord from, int dir, Coord to) {}

    /** Somewhere on the country's edge: an owned land sector beside land nobody owns (or beside sea). */
    private static Edge frontier(World w, boolean wantSea) {
        for (Sector s : w.sectors()) {
            if (s.owner() != 0 || !s.terrain().isLand()) continue;
            for (int d = 0; d < 6; d++) {
                Coord n = Hex.normalise(w, Hex.stepRaw(s.at(), d));
                if (n == null) continue;
                Sector t = w.sector(n);
                boolean sea = !t.terrain().isLand();
                if (wantSea ? sea : (!sea && !t.owned() && !t.sanctuary())) return new Edge(s.at(), d, n);
            }
        }
        return null;
    }

    @Test
    void aDeliveryTowardLandYouDoNotOwnIsRefusedAndNothingIsWritten() {
        World w = world();
        Edge e = frontier(w, false);
        assertThat(e).as("a grown country has a frontier").isNotNull();

        CommandResult r = run(w, new Command.Deliver(e.from(), "food", e.dir(), 100));
        assertThat(r.ok()).as("must be refused, not 'noted'").isFalse();
        assertThat(r.error()).contains("do not own").contains("check");
        assertThat(r.world().sector(e.from()).deliver().count()).as("no dead pipe left behind").isZero();
    }

    @Test
    void aDeliveryTowardSeaIsRefusedAndNothingIsWritten() {
        World w = world();
        Edge e = frontier(w, true);
        assertThat(e).as("a grown country reaches the coast").isNotNull();
        CommandResult r = run(w, new Command.Deliver(e.from(), "food", e.dir(), 100));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("is sea");
        assertThat(r.world().sector(e.from()).deliver().count()).isZero();
    }

    /** The other hat: a second order still replaces the first, but now it says so. */
    @Test
    void replacingADeliveryIsSaidOutLoud() {
        World w = world();
        Coord cap = w.country(0).capital();
        // two owned neighbours in different directions
        int d1 = -1, d2 = -1;
        for (int d = 0; d < 6; d++) {
            Coord n = Hex.normalise(w, Hex.stepRaw(cap, d));
            if (n != null && w.sector(n).owner() == 0 && w.sector(n).terrain().isLand()) { if (d1 < 0) d1 = d; else { d2 = d; break; } }
        }
        assertThat(d2).as("two owned neighbours of a grown capital").isGreaterThanOrEqualTo(0);

        World w1 = run(w, new Command.Deliver(cap, "food", d1, 100)).world();
        CommandResult r = run(w1, new Command.Deliver(cap, "food", d2, 250));
        assertThat(r.ok()).isTrue();
        assertThat(r.info()).contains("REPLACES").contains("above 100").contains(Hex.dirName(d1));
        assertThat(r.world().sector(cap).deliver().dir(COM.index("food"))).isEqualTo(d2);

        // the same order again is not a replacement
        CommandResult same = run(r.world(), new Command.Deliver(cap, "food", d2, 250));
        assertThat(same.info()).doesNotContain("REPLACES");
    }

    @Test
    void anExplorePartyTakesFoodWithIt() {
        World w = world();
        Edge e = frontier(w, false);
        assertThat(e).isNotNull();
        Coord cap = e.from(), to = e.to();
        // make sure the source can afford the party and the food, whatever the agent left there
        w = w.withSector(w.sector(cap).withStock(w.sector(cap).stock().plus(COM.civ, 200).plus(COM.food, 200)).withMobility(120));
        double foodBefore = w.sector(cap).stock().get(COM.food);

        CommandResult r = run(w, new Command.Explore(cap, to, 20));
        assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
        double landed = r.world().sector(to).stock().get(COM.food);
        assertThat(landed).as("the party arrives with food").isEqualTo(20 * CFG.economy().population().exploreFoodPerCivOrDefault());
        assertThat(r.world().sector(cap).stock().get(COM.food)).as("taken from the source, not conjured").isEqualTo(foodBefore - landed);
        assertThat(r.info()).contains("settle").contains("with " + String.format(java.util.Locale.ROOT, "%.1f", landed) + " food");
    }

    @Test
    void anExploreFromAStarvingSourceWarnsInsteadOfFailing() {
        World w = world();
        Edge e = frontier(w, false);
        assertThat(e).isNotNull();
        Coord cap = e.from(), to = e.to();
        // people and mobility to go, but no food at all; and barren ground, so foraging cannot cover them
        Sector src = w.sector(cap);
        World poor = w.withSector(src.withStock(src.stock().plus(COM.civ, 200).plus(COM.food, -src.stock().get(COM.food))).withMobility(120));
        Sector dest = poor.sector(to);
        poor = poor.withSector(dest.withTerrain(dest.terrain(), dest.elevation(), new Resources(0, dest.resources().minerals(), 0, 0, 0)));

        CommandResult r = run(poor, new Command.Explore(cap, to, 20));
        assertThat(r.ok()).as("still allowed — the player may know something").isTrue();
        assertThat(r.info()).contains("WARNING").contains("starve");
    }

}
