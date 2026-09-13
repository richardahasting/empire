package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.view.CountryView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the food is and where it is not (issue #160). Aggregate food looked fine while the rim
 * starved, and the player had to find the rim by hand. This runs the update the way Projection does
 * and sorts the country's sectors into <em>basins</em> — net gainers after everyone has eaten and
 * every shipment has moved — and <em>deficits</em> — net losers, or anyone who starves — then pairs
 * each deficit with its nearest basin and the direction from the basin toward it, which is the
 * {@code deliver} order that would help.
 */
public final class FoodReport {
    private FoodReport() {}

    /** {@code updatesLeft} is how many more updates the stock lasts at this rate; NaN when it is not falling. */
    public record Line(Coord at, Coord relative, String designation, double foodNow, double foodAfter, double eats, boolean starving, double updatesLeft) {
        public double delta() { return foodAfter - foodNow; }
    }
    /** "deficit ← basin": {@code dir} is the first step from the basin toward the deficit, as a deliver direction name. */
    public record Hint(Coord deficit, Coord basin, int distance, String dir) {}
    public record Result(List<Line> basins, List<Line> deficits, List<Line> steady, List<Hint> hints) {}

    public static Result of(World w, GameConfig cfg, int countryId, long seed) {
        Commodities com = Commodities.of(cfg);
        UpdateResult r = Update.run(w, cfg, seed);
        Country c = w.country(countryId);
        Set<Coord> starving = new HashSet<>();
        for (Event e : r.events()) if (e.country() == countryId && e.type().equals("starvation") && e.at() != null) starving.add(e.at());

        List<Line> basins = new ArrayList<>(), deficits = new ArrayList<>(), steady = new ArrayList<>();
        for (Sector s : w.ownedBy(countryId)) {
            if (!s.terrain().isLand()) continue;
            Sector n = r.next().sector(s.at());
            double now = s.stock().get(com.food), after = n.owner() == countryId ? n.stock().get(com.food) : 0;
            double eats = FoodMath.eatsPerUpdate(cfg, com, s);
            double delta = after - now;
            boolean starves = starving.contains(s.at());
            double left = delta < 0 ? after / -delta : Double.NaN;
            Line line = new Line(s.at(), CountryView.relative(w, c.capital(), s.at()), s.designation(), now, after, eats, starves, left);
            if (starves || delta < 0) deficits.add(line);
            else if (delta > 0) basins.add(line);
            else steady.add(line);
        }
        basins.sort(Comparator.comparingDouble((Line l) -> -l.delta()));
        deficits.sort(Comparator.comparing((Line l) -> !l.starving()).thenComparingDouble(l -> Double.isNaN(l.updatesLeft()) ? Double.MAX_VALUE : l.updatesLeft()));

        List<Hint> hints = new ArrayList<>();
        for (Line d : deficits) {
            Line best = null; int bestD = Integer.MAX_VALUE;
            for (Line b : basins) { int dist = Hex.distance(w, b.at(), d.at()); if (dist < bestD) { bestD = dist; best = b; } }
            if (best != null) hints.add(new Hint(d.relative(), best.relative(), bestD, Hex.dirName(toward(w, best.at(), d.at()))));
        }
        return new Result(List.copyOf(basins), List.copyOf(deficits), List.copyOf(steady), List.copyOf(hints));
    }

    /** The neighbour direction that brings {@code from} closest to {@code to}. */
    static int toward(World w, Coord from, Coord to) {
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int d = 0; d < 6; d++) {
            Coord n = Hex.normalise(w, Hex.stepRaw(from, d));
            if (n == null) continue;
            int dist = Hex.distance(w, n, to);
            if (dist < bestD) { bestD = dist; best = d; }
        }
        return best;
    }
}
