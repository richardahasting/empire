package org.hastingtx.empire.server.persistence;

import org.hastingtx.empire.agents.scripted.ScriptedAgent;
import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.steps.ApplyStep;
import org.hastingtx.empire.sim.Sim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Save a played world, load it back, and the state hash must be identical. Runs against
 * the real database (needs EMPIRE_TEST_DB_PASSWORD) and cleans up its game.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class WorldRepositoryRoundTripTest {
    @Autowired WorldRepository worlds;
    @Autowired GameRepository games;
    @Autowired JdbcTemplate jdbc;
    private Long gameId;

    @AfterEach
    void cleanup() { if (gameId != null) jdbc.update("DELETE FROM game WHERE id = ?", gameId); }

    private static Coord other0(World w, Coord cap) { return w.sectors().stream().filter(x -> !x.at().equals(cap)).findFirst().orElseThrow().at(); }

    @Test
    void savedWorldLoadsByteIdentical() {
        ConfigLoader.Loaded l = new ConfigLoader().loadPreset("teaching");
        GameConfig cfg = l.config();
        Sim sim = new Sim(cfg);
        World w0 = sim.newWorld(List.of("A", "B"), 11);
        World played = sim.run(w0, 5, 11, id -> new ScriptedAgent()).world();   // parcels, thresholds, dist centres, moves exist by now
        Commodities com = Commodities.of(cfg);
        // a deliver order too (issue #45): direction and threshold must survive the round trip
        Coord cap = played.country(0).capital();
        played = played.withSector(played.sector(cap).withDeliver(played.sector(cap).deliver().with(com.index("food"), 2, 250)));
        // a detection contact too (issue #75): it is in the state hash, so a dropped column fails here
        played = played.withContacts(List.of(new org.hastingtx.empire.engine.model.Contact(0, 7, 1, "submarine", cap, played.updateNumber(), 0.42)));
        // map memory (issue #64) and standing rail lanes (issue #70), both of which are their own tables
        played = played.withSeen(List.of(new org.hastingtx.empire.engine.model.SeenSector(
                0, cap, played.sector(cap).terrain(), 0, played.sector(cap).designation(), played.updateNumber())));
        // one lane with a named cargo and one that feeds thresholds, so the empty-cargo case is covered too
        Coord other = played.sectors().stream().filter(x -> x.owner() == 0 && !x.at().equals(cap)).findFirst().orElseThrow().at();
        played = played.withRailLanes(List.of(
                new org.hastingtx.empire.engine.model.RailLane(0, cap, other, List.of(com.index("food"), com.index("lcm"))),
                new org.hastingtx.empire.engine.model.RailLane(0, other, cap, List.of())));
        // a ship marked for firing on a country at peace (issue #68): the mark is its own column
        org.hastingtx.empire.engine.model.Ship marked = new org.hastingtx.empire.engine.model.Ship(played.nextShipId(), 0, "destroyer", "Vixen", cap, 77, org.hastingtx.empire.engine.model.Stocks.zero(com.size()).with(com.index("shell"), 40),
                null, null, 0, "fired on B", 45, "patrol", cap, 100, 50, 3, java.util.Map.of(1, played.updateNumber() + 3), List.of(cap, other0(played, cap)), 0)
                .tally(org.hastingtx.empire.engine.model.Ship.CAUGHT + "food", 1234.5).tally(org.hastingtx.empire.engine.model.Ship.DELIVERED + "lcm", 60);   // the manifest is its own table (issue #244)
        List<org.hastingtx.empire.engine.model.Ship> fleet = new java.util.ArrayList<>(played.ships());
        fleet.add(marked);
        played = played.withShips(fleet, marked.id() + 1);
        // a plane on air defence (issue #71): mission, op point and radius are their own columns
        played = played.withPlane(new org.hastingtx.empire.engine.model.Plane(played.nextPlaneId(), 0, "fighter_2", cap, 90, 100, 0, "", org.hastingtx.empire.engine.model.Plane.AIR_DEFENCE, other, 3));
        // a plane on a ship's deck (issue #71): the ship it is aboard is its own column
        played = played.withPlane(new org.hastingtx.empire.engine.model.Plane(played.nextPlaneId(), 0, "fighter_2", marked.at(), 100, 100, 0, "", null, null, 0, marked.id()));
        // a satellite in orbit (issue #71): orbit, theta and the update it went up are their own columns
        played = played.withPlane(new org.hastingtx.empire.engine.model.Plane(played.nextPlaneId(), 0, "spysat", other, 100, 320, 0, "")
                .inOrbit(other, false, 7).orbited(other, 0.35));
        // warheads (issue #71): one stored, one armed on the first plane above; their own table
        played = played.withNuke(new org.hastingtx.empire.engine.model.Nuke(1, 0, "fission_10kt", cap, 300, 2, 0, false));
        played = played.withNuke(new org.hastingtx.empire.engine.model.Nuke(2, 0, "fusion_5kt", cap, 320, 3, played.planes().get(0).id(), true));
        // mines (issue #71): a column on the sector
        played = played.withSector(played.sector(other).withMines(37));
        // a ship for sale with a bid on it (issue #141): its own table
        played = played.withTrade(new org.hastingtx.empire.engine.model.TradeLot(played.nextTradeId(), 0, org.hastingtx.empire.engine.model.TradeLot.SHIP, marked.id(), 25000, 1, null, played.updateNumber(), played.updateNumber() + 4));
        // the market (issue #141): a lot with a bid and one without, its own table
        played = played.withLot(new org.hastingtx.empire.engine.model.MarketLot(played.nextLotId(), 0, com.index("iron"), 400, 2.5, 1, cap, other, played.updateNumber(), played.updateNumber() + 4))
                       .withLot(new org.hastingtx.empire.engine.model.MarketLot(played.nextLotId() + 1, 1, com.index("food"), 50, 1.25, org.hastingtx.empire.engine.model.MarketLot.NOBODY, other, null, played.updateNumber(), played.updateNumber() + 4));

        gameId = games.create("roundtrip-test", "teaching", new ConfigLoader().toYaml(l.raw()), l.hash(), 11, played.width(), played.height(), played.wrapX(), played.wrapY(), null);
        worlds.saveAll(gameId, played, com);
        World loaded = worlds.load(games.find(gameId).orElseThrow(), cfg);
        assertThat(ApplyStep.hash(loaded)).isEqualTo(ApplyStep.hash(played));
        assertThat(loaded.pendingMoves()).isEqualTo(played.pendingMoves());
        assertThat(loaded.seen()).isEqualTo(played.seen());
        assertThat(loaded.railLanes()).isEqualTo(played.railLanes());
        assertThat(loaded.contacts()).isEqualTo(played.contacts());
        assertThat(loaded.ships()).isEqualTo(played.ships());
        assertThat(loaded.market()).isEqualTo(played.market());
        assertThat(loaded.trades()).isEqualTo(played.trades());
        assertThat(loaded.planes()).isEqualTo(played.planes());
        assertThat(loaded.nukes()).isEqualTo(played.nukes()).hasSize(2);
        assertThat(loaded.nextNukeId()).isEqualTo(played.nextNukeId());
        assertThat(loaded.sector(other).mines()).isEqualTo(37);
        assertThat(loaded.nextTradeId()).isEqualTo(played.nextTradeId());
        assertThat(loaded.nextLotId()).isEqualTo(played.nextLotId());
        for (int i = 0; i < played.sectors().size(); i++) assertThat(loaded.sectors().get(i).deliver()).as("deliver orders at %d", i).isEqualTo(played.sectors().get(i).deliver());

        // diff save: one more update, only changed rows written, still identical
        World next = sim.run(loaded, 1, 12, id -> new ScriptedAgent()).world();
        worlds.saveDiff(gameId, loaded, next, com);
        World loaded2 = worlds.load(games.find(gameId).orElseThrow(), cfg);
        assertThat(ApplyStep.hash(loaded2)).isEqualTo(ApplyStep.hash(next));
        assertThat(loaded2.market()).isEqualTo(next.market());
        long held = loaded2.sectors().stream().mapToLong(s -> s.held().size()).sum();
        assertThat(held).isEqualTo(next.sectors().stream().mapToLong(Sector::heldCount).sum());
    }
}
