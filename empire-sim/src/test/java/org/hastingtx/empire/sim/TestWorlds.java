package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Hand-built fixtures: a symmetric all-plains disc with one country at the centre. */
final class TestWorlds {
    private TestWorlds() {}

    static GameConfig teaching() { return new ConfigLoader().loadPreset("teaching").config(); }

    static final int SIZE = 23;
    static final Coord CENTER = new Coord(11, 11);

    /** A radius-{@code r} disc of plains (uniform resources), ocean beyond, non-wrapping. One country, capital at the centre. */
    static World disc(GameConfig cfg, int r, Map<String, Double> capitalStock) {
        Commodities com = Commodities.of(cfg);
        List<Sector> sectors = new ArrayList<>();
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            Coord c = new Coord(x, y);
            boolean land = Hex.distanceRaw(c, CENTER) <= r;
            sectors.add(Sector.blank(c, land ? Terrain.PLAINS : Terrain.OCEAN, land ? 100 : 0, land ? new Resources(80, 40, 10, 10, 5) : Resources.NONE, com.size()));
        }
        Country country = new Country(0, "Sym", CENTER, 100000, 640, Levels.ZERO, HandicapCfg.NONE, false, false, 0);
        World w = new World(SIZE, SIZE, false, false, sectors, List.of(country), List.of(), 0);
        Sector cap = w.sector(CENTER).withOwner(0).withDesignation("capital", 100).withMobility(127).withStock(Stocks.of(com.fromMap(capitalStock)));
        return w.withSector(cap);
    }

    /** Own the sector, give it a designation, efficiency, mobility, stock, thresholds and the capital as centre. */
    static World own(World w, GameConfig cfg, Coord at, String designation, double eff, double mob, Map<String, Double> stock, Map<String, Double> thresholds) {
        Commodities com = Commodities.of(cfg);
        Sector s = w.sector(at).withOwner(0).withDesignation(designation, eff).withMobility(mob).withStock(Stocks.of(com.fromMap(stock))).withDistCenter(CENTER);
        double[] th = s.thresholds().clone();
        for (var e : thresholds.entrySet()) th[com.index(e.getKey())] = e.getValue();
        return w.withSector(s.withThresholds(th));
    }

    /** Rotate every sector (and every coordinate stored in sectors and countries) 60°·k about {@code center}. */
    static final int ROTATE_RADIUS = 8;

    static World rotate(World w, Coord center, int k) {
        List<Sector> out = new ArrayList<>(w.sectors());
        for (Sector s : w.sectors()) {
            if (Hex.distanceRaw(s.at(), center) > ROTATE_RADIUS) { if (s.owned()) throw new IllegalStateException("fixture too large to rotate"); continue; }
            Coord to = Hex.rotate(s.at(), center, k);
            if (!w.inBounds(to)) throw new IllegalStateException("rotation leaves the grid at " + s.at());
            Sector r = s.withAt(to);
            if (s.distCenter() != null) r = r.withDistCenter(Hex.rotate(s.distCenter(), center, k));
            List<HeldParcel> held = new ArrayList<>();
            for (HeldParcel p : s.held()) held.add(new HeldParcel(p.commodity(), p.qty(), p.owner(), Hex.rotate(p.origin(), center, k), Hex.rotate(p.dest(), center, k), p.issuedUpdate()));
            r = r.withHeld(held);
            out.set(w.index(to), r);
        }
        List<Country> cs = new ArrayList<>();
        for (Country c : w.countries()) cs.add(c.withCapital(Hex.rotate(c.capital(), center, k)));
        List<MoveOrder> moves = new ArrayList<>();
        for (MoveOrder m : w.pendingMoves()) moves.add(new MoveOrder(m.owner(), Hex.rotate(m.from(), center, k), Hex.rotate(m.to(), center, k), m.commodity(), m.qty(), m.issuedUpdate()));
        return new World(w.width(), w.height(), false, false, out, cs, moves, w.updateNumber());
    }
}
