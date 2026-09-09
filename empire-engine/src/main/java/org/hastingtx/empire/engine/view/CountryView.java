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
        double cash,
        double btu,
        Levels levels,
        HandicapCfg handicap,
        boolean inSanctuary,
        boolean bankrupt,
        List<String> commodityIds,
        List<SectorView> sectors,
        List<String> otherCountryNames) {

    /** {@code full} is true for owned sectors; adjacent unowned sectors expose terrain and owner only. */
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
            Map<String, Delivery> deliveries) {}

    public record Delivery(String dir, double threshold) {}

    public static CountryView of(World w, GameConfig cfg, int countryId) {
        Commodities com = Commodities.of(cfg);
        Country c = w.country(countryId);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < com.size(); i++) ids.add(com.id(i));

        Set<Coord> visible = new TreeSet<>();
        for (Sector s : w.ownedBy(countryId)) { visible.add(s.at()); visible.addAll(Hex.neighbours(w, s.at())); }

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
                        s.mobility(), s.roadLevel(), s.roadTarget(), s.railLevel(), s.railTarget(), stock, th, s.distCenter(), held, s.resources(), deliveries));
            } else {
                int owner = s.sanctuary() ? Sector.NOBODY : s.owner();   // sanctuaries are invisible
                views.add(new SectorView(at, rel, false, s.terrain().id(), s.elevation(), owner, null, 0, 0, 0, 0, 0, 0,
                        Map.of(), Map.of(), null, Map.of(), null, Map.of()));
            }
        }
        List<String> others = new ArrayList<>();
        for (Country o : w.countries()) if (o.id() != countryId) others.add(o.name());
        return new CountryView(countryId, c.name(), w.updateNumber(), c.capital(), w.wrapX(), w.wrapY(), c.cash(), c.btu(), c.levels(), c.handicap(),
                c.inSanctuary(), c.bankrupt(), ids, views, others);
    }

    /** Player-facing coordinates: offset from the capital, shortest way round when wrapped. */
    public static Coord relative(World w, Coord capital, Coord at) {
        int dx = at.x() - capital.x(), dy = at.y() - capital.y();
        if (w.wrapX()) { if (dx > w.width() / 2) dx -= w.width(); else if (dx < -w.width() / 2) dx += w.width(); }
        if (w.wrapY()) { if (dy > w.height() / 2) dy -= w.height(); else if (dy < -w.height() / 2) dy += w.height(); }
        return new Coord(dx, dy);
    }
}
