package org.hastingtx.empire.server.persistence;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.*;

/**
 * Maps the immutable engine World to the normalised tables and back, by hand. Saves are
 * diffs: only sectors whose content changed are written, so a single command costs a few
 * rows and an update costs one batch.
 */
@Repository
public class WorldRepository {
    private final JdbcTemplate jdbc;
    private final Json json;

    public WorldRepository(JdbcTemplate jdbc, Json json) { this.jdbc = jdbc; this.json = json; }

    // ----------------------------------------------------------------------------------- save
    @Transactional
    public void saveAll(long gameId, World w, Commodities com) {
        List<Sector> all = w.sectors();
        writeSectors(gameId, all, com);
        writeCountries(gameId, w.countries(), true);
        writeMoves(gameId, w.pendingMoves(), com);
        writeRail(gameId, w.pendingRail(), com);
        writeShips(gameId, w, com);
        jdbc.update("UPDATE game SET update_number = ? WHERE id = ?", w.updateNumber(), gameId);
    }

    @Transactional
    public void saveDiff(long gameId, World before, World after, Commodities com) {
        List<Sector> changed = new ArrayList<>();
        for (int i = 0; i < after.sectors().size(); i++) {
            Sector a = after.sectors().get(i), b = before.sectors().get(i);
            if (!same(a, b)) changed.add(a);
        }
        writeSectors(gameId, changed, com);
        writeCountries(gameId, after.countries(), false);
        if (!after.pendingMoves().equals(before.pendingMoves())) writeMoves(gameId, after.pendingMoves(), com);
        if (!after.pendingRail().equals(before.pendingRail())) writeRail(gameId, after.pendingRail(), com);
        if (!after.ships().equals(before.ships()) || after.nextShipId() != before.nextShipId()) writeShips(gameId, after, com);
        jdbc.update("UPDATE game SET update_number = ? WHERE id = ?", after.updateNumber(), gameId);
    }

    static boolean same(Sector a, Sector b) {
        return a.owner() == b.owner() && a.designation().equals(b.designation()) && a.efficiency() == b.efficiency() && a.mobility() == b.mobility()
                && a.stock().equals(b.stock()) && Arrays.equals(a.thresholds(), b.thresholds()) && Objects.equals(a.distCenter(), b.distCenter())
                && a.roadLevel() == b.roadLevel() && a.railLevel() == b.railLevel() && a.radarLevel() == b.radarLevel()
                && a.held().equals(b.held()) && a.sanctuary() == b.sanctuary() && a.terrain() == b.terrain() && a.roadTarget() == b.roadTarget() && a.railTarget() == b.railTarget()
                && a.deliver().equals(b.deliver()) && a.resources().equals(b.resources()) && a.elevation() == b.elevation();
    }

    private void writeSectors(long gameId, List<Sector> sectors, Commodities com) {
        if (sectors.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO sector (game_id, x, y, terrain, elevation, fertility, minerals, gold, oil, uranium, owner, designation, efficiency, mobility,
                                    road_level, rail_level, radar_level, dist_x, dist_y, sanctuary, road_target, rail_target)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (game_id, x, y) DO UPDATE SET terrain = EXCLUDED.terrain, elevation = EXCLUDED.elevation, fertility = EXCLUDED.fertility,
                    minerals = EXCLUDED.minerals, gold = EXCLUDED.gold, oil = EXCLUDED.oil, uranium = EXCLUDED.uranium, owner = EXCLUDED.owner,
                    designation = EXCLUDED.designation, efficiency = EXCLUDED.efficiency, mobility = EXCLUDED.mobility, road_level = EXCLUDED.road_level,
                    rail_level = EXCLUDED.rail_level, radar_level = EXCLUDED.radar_level, dist_x = EXCLUDED.dist_x, dist_y = EXCLUDED.dist_y, sanctuary = EXCLUDED.sanctuary, road_target = EXCLUDED.road_target, rail_target = EXCLUDED.rail_target""",
                sectors, 500, (PreparedStatement ps, Sector s) -> {
                    Resources r = s.resources();
                    ps.setLong(1, gameId); ps.setInt(2, s.at().x()); ps.setInt(3, s.at().y()); ps.setString(4, s.terrain().id()); ps.setInt(5, s.elevation());
                    ps.setInt(6, r.fertility()); ps.setInt(7, r.minerals()); ps.setInt(8, r.gold()); ps.setInt(9, r.oil()); ps.setInt(10, r.uranium());
                    ps.setInt(11, s.owner()); ps.setString(12, s.designation()); ps.setDouble(13, s.efficiency()); ps.setDouble(14, s.mobility());
                    ps.setDouble(15, s.roadLevel()); ps.setDouble(16, s.railLevel()); ps.setDouble(17, s.radarLevel());
                    if (s.distCenter() == null) { ps.setNull(18, java.sql.Types.INTEGER); ps.setNull(19, java.sql.Types.INTEGER); }
                    else { ps.setInt(18, s.distCenter().x()); ps.setInt(19, s.distCenter().y()); }
                    ps.setBoolean(20, s.sanctuary());
                    ps.setDouble(21, s.roadTarget());
                    ps.setDouble(22, s.railTarget());
                });
        List<Object[]> stockRows = new ArrayList<>();
        List<Object[]> parcelRows = new ArrayList<>();
        for (Sector s : sectors) {
            for (int c = 0; c < com.size(); c++) {
                double th = s.thresholds()[c];
                boolean dl = s.deliver().has(c);
                stockRows.add(new Object[] {gameId, s.at().x(), s.at().y(), com.id(c), s.stock().get(c), Double.isNaN(th) ? null : th, dl ? s.deliver().dir(c) : null, dl ? s.deliver().threshold(c) : null});
            }
            for (HeldParcel p : s.held())
                parcelRows.add(new Object[] {gameId, s.at().x(), s.at().y(), com.id(p.commodity()), p.qty(), p.owner(), p.origin().x(), p.origin().y(), p.dest().x(), p.dest().y(), p.issuedUpdate(), p.mode()});
        }
        jdbc.batchUpdate("""
                INSERT INTO sector_stock (game_id, x, y, commodity, qty, threshold, deliver_dir, deliver_threshold) VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (game_id, x, y, commodity) DO UPDATE SET qty = EXCLUDED.qty, threshold = EXCLUDED.threshold, deliver_dir = EXCLUDED.deliver_dir, deliver_threshold = EXCLUDED.deliver_threshold""", stockRows);
        List<Object[]> keys = new ArrayList<>();
        for (Sector s : sectors) keys.add(new Object[] {gameId, s.at().x(), s.at().y()});
        jdbc.batchUpdate("DELETE FROM held_parcel WHERE game_id = ? AND x = ? AND y = ?", keys);
        if (!parcelRows.isEmpty())
            jdbc.batchUpdate("INSERT INTO held_parcel (game_id, x, y, commodity, qty, owner, origin_x, origin_y, dest_x, dest_y, issued_update, mode) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", parcelRows);
    }

    private void writeCountries(long gameId, List<Country> countries, boolean insert) {
        for (Country c : countries) {
            if (insert) {
                jdbc.update("""
                        INSERT INTO country (game_id, country_id, name, capital_x, capital_y, cash, btu, tech, research, education, happiness, handicap, in_sanctuary, bankrupt, plague_left, controller)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,'none')
                        ON CONFLICT (game_id, country_id) DO UPDATE SET capital_x = EXCLUDED.capital_x, capital_y = EXCLUDED.capital_y, cash = EXCLUDED.cash, btu = EXCLUDED.btu,
                            tech = EXCLUDED.tech, research = EXCLUDED.research, education = EXCLUDED.education, happiness = EXCLUDED.happiness, handicap = EXCLUDED.handicap,
                            in_sanctuary = EXCLUDED.in_sanctuary, bankrupt = EXCLUDED.bankrupt, plague_left = EXCLUDED.plague_left""",
                        gameId, c.id(), c.name(), c.capital().x(), c.capital().y(), c.cash(), c.btu(), c.levels().tech(), c.levels().research(), c.levels().education(),
                        c.levels().happiness(), json.write(c.handicap()), c.inSanctuary(), c.bankrupt(), c.plagueUpdatesLeft());
            } else {
                jdbc.update("""
                        UPDATE country SET capital_x = ?, capital_y = ?, cash = ?, btu = ?, tech = ?, research = ?, education = ?, happiness = ?, handicap = ?::jsonb,
                            in_sanctuary = ?, bankrupt = ?, plague_left = ? WHERE game_id = ? AND country_id = ?""",
                        c.capital().x(), c.capital().y(), c.cash(), c.btu(), c.levels().tech(), c.levels().research(), c.levels().education(), c.levels().happiness(),
                        json.write(c.handicap()), c.inSanctuary(), c.bankrupt(), c.plagueUpdatesLeft(), gameId, c.id());
            }
        }
    }

    /** Few ships, so the whole fleet is rewritten whenever any of it changed. */
    private void writeShips(long gameId, World w, Commodities com) {
        jdbc.update("DELETE FROM ship WHERE game_id = ?", gameId);
        jdbc.update("UPDATE game SET next_ship_id = ? WHERE id = ?", w.nextShipId(), gameId);
        if (w.ships().isEmpty()) return;
        List<Object[]> rows = new ArrayList<>(), stock = new ArrayList<>();
        for (Ship s : w.ships()) {
            Ship.Lane l = s.lane();
            rows.add(new Object[] {gameId, s.id(), s.owner(), s.cls(), s.name() == null ? "" : s.name(), s.at().x(), s.at().y(), s.efficiency(),
                    s.dest() == null ? null : s.dest().x(), s.dest() == null ? null : s.dest().y(),
                    l == null ? null : l.from().x(), l == null ? null : l.from().y(), l == null ? null : l.to().x(), l == null ? null : l.to().y(),
                    l == null ? null : l.cargo().stream().map(com::id).collect(java.util.stream.Collectors.joining(",")), l != null && l.outbound(), s.built(), s.note() == null ? "" : s.note(), s.tech()});
            for (int c = 0; c < com.size(); c++) if (s.stock().get(c) > 0) stock.add(new Object[] {gameId, s.id(), com.id(c), s.stock().get(c)});
        }
        jdbc.batchUpdate("INSERT INTO ship (game_id, id, owner, class, name, x, y, efficiency, dest_x, dest_y, lane_from_x, lane_from_y, lane_to_x, lane_to_y, lane_cargo, lane_outbound, built, note, tech) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows);
        if (!stock.isEmpty()) jdbc.batchUpdate("INSERT INTO ship_stock (game_id, ship_id, commodity, qty) VALUES (?,?,?,?)", stock);
    }

    private void writeRail(long gameId, List<RailOrder> orders, Commodities com) {
        jdbc.update("DELETE FROM rail_order WHERE game_id = ?", gameId);
        List<Object[]> rows = new ArrayList<>();
        for (RailOrder o : orders) rows.add(new Object[] {gameId, o.owner(), o.from().x(), o.from().y(), o.to().x(), o.to().y(), com.id(o.commodity()), o.qty(), o.issuedUpdate()});
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO rail_order (game_id, owner, from_x, from_y, to_x, to_y, commodity, qty, issued_update) VALUES (?,?,?,?,?,?,?,?,?)", rows);
    }

    private void writeMoves(long gameId, List<MoveOrder> moves, Commodities com) {
        jdbc.update("DELETE FROM move_order WHERE game_id = ?", gameId);
        List<Object[]> rows = new ArrayList<>();
        for (MoveOrder m : moves) rows.add(new Object[] {gameId, m.owner(), m.from().x(), m.from().y(), m.to().x(), m.to().y(), com.id(m.commodity()), m.qty(), m.issuedUpdate()});
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO move_order (game_id, owner, from_x, from_y, to_x, to_y, commodity, qty, issued_update) VALUES (?,?,?,?,?,?,?,?,?)", rows);
    }

    // ----------------------------------------------------------------------------------- load
    public World load(GameRow g, GameConfig cfg) {
        Commodities com = Commodities.of(cfg);
        int n = com.size();
        Sector[] sectors = new Sector[g.width() * g.height()];
        jdbc.query("SELECT * FROM sector WHERE game_id = ?", rs -> {
            Coord at = new Coord(rs.getInt("x"), rs.getInt("y"));
            int dx = rs.getInt("dist_x"); boolean noDist = rs.wasNull(); int dy = rs.getInt("dist_y");
            Sector s = Sector.blank(at, Terrain.of(rs.getString("terrain")), rs.getInt("elevation"),
                    new Resources(rs.getInt("fertility"), rs.getInt("minerals"), rs.getInt("gold"), rs.getInt("oil"), rs.getInt("uranium")), n)
                    .withOwner(rs.getInt("owner")).withDesignation(rs.getString("designation"), rs.getDouble("efficiency")).withMobility(rs.getDouble("mobility"))
                    .withRoadLevel(rs.getDouble("road_level")).withRoadTarget(rs.getDouble("road_target")).withRailLevel(rs.getDouble("rail_level")).withRailTarget(rs.getDouble("rail_target")).withDistCenter(noDist ? null : new Coord(dx, dy)).withSanctuary(rs.getBoolean("sanctuary"));
            sectors[at.y() * g.width() + at.x()] = s;
        }, g.id());
        double[][] stock = new double[sectors.length][n];
        double[][] th = new double[sectors.length][n];
        for (double[] row : th) Arrays.fill(row, Double.NaN);
        DeliverOrders[] dl = new DeliverOrders[sectors.length];
        jdbc.query("SELECT x, y, commodity, qty, threshold, deliver_dir, deliver_threshold FROM sector_stock WHERE game_id = ?", rs -> {
            int i = rs.getInt("y") * g.width() + rs.getInt("x");
            int c = com.index(rs.getString("commodity"));
            stock[i][c] = rs.getDouble("qty");
            double t = rs.getDouble("threshold"); if (!rs.wasNull()) th[i][c] = t;
            int d = rs.getInt("deliver_dir");
            if (!rs.wasNull()) { if (dl[i] == null) dl[i] = DeliverOrders.none(n); dl[i] = dl[i].with(c, d, rs.getDouble("deliver_threshold")); }
        }, g.id());
        Map<Integer, List<HeldParcel>> held = new HashMap<>();
        jdbc.query("SELECT * FROM held_parcel WHERE game_id = ? ORDER BY id", rs -> {
            int i = rs.getInt("y") * g.width() + rs.getInt("x");
            held.computeIfAbsent(i, k -> new ArrayList<>()).add(new HeldParcel(com.index(rs.getString("commodity")), rs.getDouble("qty"), rs.getInt("owner"),
                    new Coord(rs.getInt("origin_x"), rs.getInt("origin_y")), new Coord(rs.getInt("dest_x"), rs.getInt("dest_y")), rs.getLong("issued_update"), rs.getString("mode")));
        }, g.id());
        List<Sector> list = new ArrayList<>(sectors.length);
        for (int i = 0; i < sectors.length; i++) {
            if (sectors[i] == null) throw new IllegalStateException("game " + g.id() + " missing sector " + i);
            Sector s = sectors[i].withStock(Stocks.of(stock[i])).withThresholds(th[i]);
            if (dl[i] != null) s = s.withDeliver(dl[i]);
            if (held.containsKey(i)) s = s.withHeld(held.get(i));
            list.add(s);
        }
        List<Country> countries = jdbc.query("SELECT * FROM country WHERE game_id = ? ORDER BY country_id", (rs, i) -> new Country(
                rs.getInt("country_id"), rs.getString("name"), new Coord(rs.getInt("capital_x"), rs.getInt("capital_y")), rs.getDouble("cash"), rs.getDouble("btu"),
                new Levels(rs.getDouble("tech"), rs.getDouble("research"), rs.getDouble("education"), rs.getDouble("happiness")),
                json.read(rs.getString("handicap"), HandicapCfg.class), rs.getBoolean("in_sanctuary"), rs.getBoolean("bankrupt"), rs.getInt("plague_left")), g.id());
        List<MoveOrder> moves = jdbc.query("SELECT * FROM move_order WHERE game_id = ? ORDER BY id", (rs, i) -> new MoveOrder(rs.getInt("owner"),
                new Coord(rs.getInt("from_x"), rs.getInt("from_y")), new Coord(rs.getInt("to_x"), rs.getInt("to_y")), com.index(rs.getString("commodity")), rs.getDouble("qty"), rs.getLong("issued_update")), g.id());
        List<RailOrder> rail = jdbc.query("SELECT * FROM rail_order WHERE game_id = ? ORDER BY id", (rs, i) -> new RailOrder(rs.getInt("owner"),
                new Coord(rs.getInt("from_x"), rs.getInt("from_y")), new Coord(rs.getInt("to_x"), rs.getInt("to_y")), com.index(rs.getString("commodity")), rs.getDouble("qty"), rs.getLong("issued_update")), g.id());
        Map<Long, double[]> shipStock = new HashMap<>();
        jdbc.query("SELECT ship_id, commodity, qty FROM ship_stock WHERE game_id = ?", rs -> {
            shipStock.computeIfAbsent(rs.getLong("ship_id"), k -> new double[n])[com.index(rs.getString("commodity"))] = rs.getDouble("qty");
        }, g.id());
        List<Ship> ships = jdbc.query("SELECT * FROM ship WHERE game_id = ? ORDER BY id", (rs, i) -> {
            long id = rs.getLong("id");
            int dx = rs.getInt("dest_x"); boolean noDest = rs.wasNull(); int dy = rs.getInt("dest_y");
            int fx = rs.getInt("lane_from_x"); boolean noLane = rs.wasNull(); int fy = rs.getInt("lane_from_y"); int tx = rs.getInt("lane_to_x"); int ty = rs.getInt("lane_to_y");
            Ship.Lane lane = null;
            if (!noLane) {
                String cargo = rs.getString("lane_cargo");
                List<Integer> cs = new ArrayList<>();
                if (cargo != null && !cargo.isBlank()) for (String k : cargo.split(",")) cs.add(com.index(k.trim()));
                lane = new Ship.Lane(new Coord(fx, fy), new Coord(tx, ty), cs, rs.getBoolean("lane_outbound"));
            }
            double[] st = shipStock.getOrDefault(id, new double[n]);
            return new Ship(id, rs.getInt("owner"), rs.getString("class"), rs.getString("name"), new Coord(rs.getInt("x"), rs.getInt("y")), rs.getDouble("efficiency"),
                    Stocks.of(st), noDest ? null : new Coord(dx, dy), lane, rs.getLong("built"), rs.getString("note"), rs.getDouble("tech"));
        }, g.id());
        Long nextShip = jdbc.queryForObject("SELECT next_ship_id FROM game WHERE id = ?", Long.class, g.id());
        return new World(g.width(), g.height(), g.wrapX(), g.wrapY(), list, countries, moves, g.updateNumber(), rail, ships, nextShip == null ? 1 : nextShip);
    }
}
