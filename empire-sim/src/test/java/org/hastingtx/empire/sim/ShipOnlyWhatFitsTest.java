package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Flow;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.hastingtx.empire.engine.update.Event;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing is shipped in order to be destroyed, and nothing is shipped in fractions (issue #101).
 *
 * <p>Both were found in a played game, not in a fixture: 557 food carried across the map and spoiled on
 * arrival at a sector that was already full, and sector notes reading "received 0.2 lcm".
 */
class ShipOnlyWhatFitsTest {
    private static final GameConfig CFG = TestWorlds.teaching();
    private static final Commodities COM = Commodities.of(CFG);
    private static final int FOOD = COM.food, IRON = COM.index("iron");
    private static final Coord CAP = TestWorlds.CENTER;
    private static final Coord SHED = Hex.stepRaw(CAP, 0, 1);
    private static final Coord FARM = Hex.stepRaw(CAP, 0, 2);

    /** A game with no configured lot size — which is what every game created before #77 is running. */
    private static GameConfig unquantised() {
        ConfigLoader ldr = new ConfigLoader();
        ConfigLoader.Loaded l = ldr.loadPreset("teaching");
        Map<String, Object> raw = new java.util.LinkedHashMap<>(l.raw());
        @SuppressWarnings("unchecked")
        Map<String, Object> dist = new java.util.LinkedHashMap<>((Map<String, Object>) raw.get("distribution"));
        dist.put("quantum", null);
        raw.put("distribution", dist);
        return ldr.loadYaml(ldr.toYaml(raw)).config();
    }

    @Test
    void afullDestinationIsNotShippedTo() {
        // the warehouse is stuffed to its capacity with food; the farm is well over its own threshold
        World w = TestWorlds.disc(CFG, 4, Map.of("civ", 500.0, "food", 500.0));
        w = TestWorlds.own(w, CFG, SHED, "warehouse", 100, 127, Map.of("civ", 400.0), Map.of());
        w = TestWorlds.own(w, CFG, FARM, "agribusiness", 100, 127, Map.of("civ", 400.0, "food", 4000.0), Map.of("food", 100.0));
        w = w.withSector(w.sector(FARM).withDistCenter(SHED));

        Sector shed = w.sector(SHED);
        double cap = 10_000 * 10;   // default capacity x the warehouse's store multiplier
        w = w.withSector(shed.withStock(shed.stock().with(FOOD, cap)));

        UpdateResult r = Update.run(w, CFG, 5);

        assertThat(r.events().stream().filter(e -> "spoilage".equals(e.type()) && e.at() != null && e.at().equals(SHED)))
                .describedAs("nothing was carried to the warehouse only to be destroyed there")
                .isEmpty();
        assertThat(r.next().sector(FARM).stock().get(FOOD))
                .describedAs("the food stayed at the farm rather than being shipped into a full shed")
                .isGreaterThan(3000.0);
    }

    @Test
    void whatDoesNotFitStaysBehindAndTheRestGoes() {
        // room for only a little: the shipment is trimmed to what fits, not cancelled and not spoiled
        World w = TestWorlds.disc(CFG, 4, Map.of("civ", 500.0, "food", 500.0));
        w = TestWorlds.own(w, CFG, SHED, "warehouse", 100, 127, Map.of("civ", 400.0), Map.of());
        w = TestWorlds.own(w, CFG, FARM, "agribusiness", 100, 127, Map.of("civ", 400.0, "food", 4000.0), Map.of("food", 100.0));
        w = w.withSector(w.sector(FARM).withDistCenter(SHED));
        Sector shed = w.sector(SHED);
        w = w.withSector(shed.withStock(shed.stock().with(FOOD, 10_000 * 10 - 300)));   // room for 300

        UpdateResult r = Update.run(w, CFG, 5);

        assertThat(r.events().stream().filter(e -> "spoilage".equals(e.type()) && e.at() != null && e.at().equals(SHED))).isEmpty();
        assertThat(r.next().sector(SHED).stock().get(FOOD))
                .describedAs("filled to the brim and no further")
                .isLessThanOrEqualTo(10_000.0 * 10);
    }

    @Test
    void shipmentsAreWholeEvenWithNoLotSizeConfigured() {
        GameConfig cfg = unquantised();
        assertThat(cfg.distribution().quantumOr0()).describedAs("the fixture really is unquantised").isZero();

        Sim sim = new Sim(cfg);
        Sim.Result r = sim.run(sim.newWorld(List.of("A", "B"), 11), 20, 11,
                id -> new org.hastingtx.empire.agents.scripted.ScriptedAgent());

        for (Flow f : r.lastFlows())
            assertThat(f.qtyMoved()).describedAs("you cannot ship a fifth of a girder: %s", f)
                    .isEqualTo(Math.floor(f.qtyMoved()));

        // and nothing fractional ended up sitting in a sector or a parcel
        for (Sector s : r.world().sectors()) {
            for (int c = 0; c < COM.size(); c++)
                assertThat(s.stock().get(c)).isEqualTo(Math.floor(s.stock().get(c)));
            for (HeldParcel p : s.held())
                assertThat(p.qty()).describedAs("a parcel in transit is whole units too").isEqualTo(Math.floor(p.qty()));
        }
    }
}
