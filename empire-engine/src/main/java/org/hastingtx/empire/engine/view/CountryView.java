package org.hastingtx.empire.engine.view;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.HandicapCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;

import java.util.*;

/**
 * Everything one country is allowed to know. The only projection of the world that ever
 * leaves the engine toward a player, human or agent. Contains no controller information
 * for any country, by construction: there is no field for it.
 */
public record CountryView(
        int countryId,
        String name,
        long updateNumber,
        Coord capital,
        boolean wrapX,
        boolean wrapY,
        /** Grid size, so a client can reason about adjacency across the wrap (issue #54). */
        int width,
        int height,
        double cash,
        double btu,
        Levels levels,
        HandicapCfg handicap,
        boolean inSanctuary,
        boolean bankrupt,
        List<String> commodityIds,
        List<SectorView> sectors,
        List<String> otherCountryNames,
        /** Your ships (issue #56). */
        List<ShipView> ships,
        /** What your radar and lookouts have seen of other people's ships (issue #75). */
        List<ContactView> contacts,
        /** Your standing depot-to-depot rail runs (issue #70). */
        List<RailLaneView> railLanes,
        /** Trains of yours stopped on the line, part-way to somewhere (issue #70). */
        List<TrainView> trains) {

    public record ShipView(long id, String cls, String name, Coord at, Coord relative, double efficiency, Map<String, Double> stock, double load, double hold,
                           Coord dest, Coord destRelative, LaneView lane, String note, boolean docked, double tech, int hexesPerUpdate, String mission, Coord homeRelative) {}
    /**
     * A sighting as its holder is allowed to read it. {@code band} is firm | probable | faint and
     * {@code age} is updates since the sighting, so a stale mark can be drawn dimmer. Anything the
     * band does not warrant is null — see {@link #reveal}.
     */
    public record ContactView(Coord at, Coord relative, String band, long age, double confidence, String cls, String ownerName) {}

    public record LaneView(Coord from, Coord to, Coord fromRelative, Coord toRelative, List<String> cargo, boolean outbound) {}

    /**
     * A standing rail run. An empty {@code cargo} means the lane keeps {@code to}'s thresholds topped
     * up rather than pushing a named list (issue #70).
     */
    public record RailLaneView(Coord from, Coord to, Coord fromRelative, Coord toRelative, List<String> cargo) {}

    /** A train part-way along its line: what it carries, where it stopped, where it is bound. */
    public record TrainView(Coord at, Coord relative, String commodity, double qty, Coord dest, Coord destRelative) {}

    /**
     * {@code full} is true for owned sectors; adjacent unowned sectors expose terrain and owner only.
     *
     * <p>{@code remembered} marks a sector nobody is looking at: it is on the chart because this country
     * saw it once (issue #64), and everything in it is as it was at {@code seenUpdate}, not as it is.
     * {@code age} is how many updates ago that was, so a client can dim it by how stale it is. A current
     * sector has {@code remembered} false and {@code age} 0.
     */
    public record SectorView(
            Coord at,
            Coord relative,
            boolean full,
            String terrain,
            int elevation,
            int owner,
            String designation,
            double efficiency,
            double mobility,
            double roadLevel,
            double roadTarget,
            double railLevel,
            double railTarget,
            Map<String, Double> stock,
            Map<String, Double> thresholds,
            Coord distCenter,
            Map<String, Double> held,
            Resources resources,
            /** Standing delivery orders by commodity (issue #45). */
            Map<String, Delivery> deliveries,
            /** A sanctuary, and whose (owner's name for a foreign sector, null for yours or nobody's). Issue #54. */
            boolean sanctuary,
            String ownerName,
            boolean remembered,
            long seenUpdate,
            long age) {}

    public record Delivery(String dir, double threshold) {}

    public static CountryView of(World w, GameConfig cfg, int countryId) {
        Commodities com = Commodities.of(cfg);
        Country c = w.country(countryId);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < com.size(); i++) ids.add(com.id(i));

        Set<Coord> visible = Visibility.of(w, cfg, countryId);

        List<SectorView> views = new ArrayList<>();
        for (Coord at : visible) {
            Sector s = w.sector(at);
            Coord rel = relative(w, c.capital(), at);
            if (s.owner() == countryId) {
                Map<String, Double> stock = new LinkedHashMap<>(), th = new LinkedHashMap<>(), held = new LinkedHashMap<>();
                Map<String, Delivery> deliveries = new LinkedHashMap<>();
                for (int i = 0; i < com.size(); i++) {
                    stock.put(com.id(i), s.stock().get(i));
                    if (s.hasThreshold(i)) th.put(com.id(i), s.threshold(i));
                    if (s.deliver().has(i)) deliveries.put(com.id(i), new Delivery(Hex.dirName(s.deliver().dir(i)), s.deliver().threshold(i)));
                }
                for (HeldParcel p : s.held()) held.merge(com.id(p.commodity()), p.qty(), Double::sum);
                views.add(new SectorView(at, rel, true, s.terrain().id(), s.elevation(), s.owner(), s.designation(), s.efficiency(),
                        s.mobility(), s.roadLevel(), s.roadTarget(), s.railLevel(), s.railTarget(), stock, th, s.distCenter(), held, s.resources(), deliveries, s.sanctuary(), null,
                        false, w.updateNumber(), 0));
            } else {
                // a neighbour: terrain and owner only. Sanctuaries are shown as such, with the owner's name (the original marked them 's').
                String ownerName = s.owned() ? w.country(s.owner()).name() : null;
                // the sea shows its fishing grounds (issue #56); land keeps its resources to itself until explored
                Resources res = s.terrain() == Terrain.OCEAN ? new Resources(s.resources().fertility(), 0, 0, 0, 0) : null;
                boolean sea = s.terrain() == Terrain.OCEAN;
                views.add(new SectorView(at, rel, false, s.terrain().id(), s.elevation(), s.owner(), null, 0, 0, 0, 0, sea ? s.railLevel() : 0, sea ? s.railTarget() : 0,
                        Map.of(), Map.of(), null, Map.of(), res, Map.of(), s.sanctuary(), ownerName,
                        false, w.updateNumber(), 0));
            }
        }
        // ...and everything this country remembers but cannot currently see (issue #64). Drawn from the
        // memory, not from the world: the point is that it says what was there, not what is.
        for (SeenSector m : w.seenBy(countryId)) {
            if (visible.contains(m.at())) continue;
            String ownerName = m.sectorOwner() == Sector.NOBODY ? null
                    : m.sectorOwner() < w.countries().size() ? w.country(m.sectorOwner()).name() : null;
            views.add(new SectorView(m.at(), relative(w, c.capital(), m.at()), false, m.terrain().id(), 0, m.sectorOwner(),
                    m.sectorOwner() == countryId ? m.designation() : null, 0, 0, 0, 0, 0, 0,
                    Map.of(), Map.of(), null, Map.of(), null, Map.of(), false, ownerName,
                    true, m.seenUpdate(), m.age(w.updateNumber())));
        }
        views.sort(java.util.Comparator.comparing(SectorView::at));

        List<String> others = new ArrayList<>();
        for (Country o : w.countries()) if (o.id() != countryId) others.add(o.name());
        List<ShipView> ships = new ArrayList<>();
        for (Ship sh : w.ships()) {
            if (sh.owner() != countryId) continue;
            Map<String, Double> st = new LinkedHashMap<>();
            for (int i = 0; i < com.size(); i++) if (sh.stock().get(i) > 1e-9) st.put(com.id(i), sh.stock().get(i));
            double hold = cfg.units().ships() == null ? 0 : cfg.units().ships().shipClass(sh.cls()).hold();
            Sector here = w.sector(sh.at());
            boolean docked = here.owner() == countryId && cfg.sectorType(here.designation()).hasFlag("builds_ships");
            LaneView lane = sh.lane() == null ? null : new LaneView(sh.lane().from(), sh.lane().to(), relative(w, c.capital(), sh.lane().from()), relative(w, c.capital(), sh.lane().to()),
                    sh.lane().cargo().stream().map(com::id).toList(), sh.lane().outbound());
            int hexes = cfg.units().ships() == null ? 0 : cfg.units().ships().range(cfg.units().ships().shipClass(sh.cls()), sh.tech(), sh.efficiency());
            ships.add(new ShipView(sh.id(), sh.cls(), sh.name(), sh.at(), relative(w, c.capital(), sh.at()), sh.efficiency(), st, sh.load(), hold,
                    sh.dest(), sh.dest() == null ? null : relative(w, c.capital(), sh.dest()), lane, sh.note(), docked, sh.tech(), hexes, sh.mission(), sh.home() == null ? null : relative(w, c.capital(), sh.home())));
        }
        List<ContactView> contacts = new ArrayList<>();
        if (cfg.detection() != null) for (Contact ct : w.contactsOf(countryId)) {
            String band = cfg.detection().band(ct.confidence());
            contacts.add(reveal(w, cfg, c.capital(), ct, band, w.updateNumber() - ct.seenUpdate()));
        }
        // standing rail runs and the trains currently on them (issue #70)
        List<RailLaneView> railLanes = new ArrayList<>();
        for (RailLane l : w.railLanesOf(countryId))
            railLanes.add(new RailLaneView(l.from(), l.to(), relative(w, c.capital(), l.from()), relative(w, c.capital(), l.to()),
                    l.cargo().stream().map(com::id).toList()));
        List<TrainView> trains = new ArrayList<>();
        for (Coord at : visible) {
            for (HeldParcel p : w.sector(at).held()) {
                if (!p.rail() || p.owner() != countryId) continue;
                trains.add(new TrainView(at, relative(w, c.capital(), at), com.id(p.commodity()), p.qty(), p.dest(), relative(w, c.capital(), p.dest())));
            }
        }

        return new CountryView(countryId, c.name(), w.updateNumber(), c.capital(), w.wrapX(), w.wrapY(), w.width(), w.height(), c.cash(), c.btu(), c.levels(), c.handicap(),
                c.inSanctuary(), c.bankrupt(), ids, views, others, ships, contacts, railLanes, trains);
    }

    /**
     * What a sighting is allowed to tell its holder, by confidence band.
     *
     * <p>TODO(richard, issue #75): decide the disclosure rule. This placeholder reveals everything at
     * every band, which makes a faint contact read exactly like a firm one and gives submarines
     * nothing to hide behind. The bands are firm (p >= 0.8), probable (p >= 0.4) and faint below that.
     *
     * @param ct   the true sighting
     * @param band firm | probable | faint
     * @param age  updates since it was made (0 = seen this update)
     */
    static ContactView reveal(World w, GameConfig cfg, Coord capital, Contact ct, String band, long age) {
        String ownerName = w.country(ct.targetOwner()).name();
        return new ContactView(ct.at(), relative(w, capital, ct.at()), band, age, ct.confidence(), ct.cls(), ownerName);
    }

    /** Player-facing coordinates: offset from the capital, shortest way round when wrapped. */
    public static Coord relative(World w, Coord capital, Coord at) {
        int dx = at.x() - capital.x(), dy = at.y() - capital.y();
        if (w.wrapX()) { if (dx > w.width() / 2) dx -= w.width(); else if (dx < -w.width() / 2) dx += w.width(); }
        if (w.wrapY()) { if (dy > w.height() / 2) dy -= w.height(); else if (dy < -w.height() / 2) dy += w.height(); }
        return new Coord(dx, dy);
    }
}
