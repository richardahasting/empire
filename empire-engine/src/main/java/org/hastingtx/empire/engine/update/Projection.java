package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.view.CountryView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "What happens to me at the next update if nothing changes?" — answered by actually running
 * the update on the current world (it is a pure function) and diffing one country. Exact up
 * to the seed; other countries' pending commands are, of course, unknown.
 */
public final class Projection {
    private Projection() {}

    /**
     * One sector the next update will hurt (issue #152): where, in the player's own coordinates, how
     * much, and a hint at why — a count alone sent people to the census to hunt for the rim.
     */
    public record Trouble(Coord at, String designation, double amount, String hint) {}

    public record Result(long forUpdate, double cashNow, double cashAfter, double civNow, double civAfter, double foodNow, double foodAfter,
                         double btuNow, double btuAfter, int starvingSectors, int spoilingSectors, int flowsCompleted, int flowsHeld,
                         List<Trouble> starving, List<Trouble> spoiling,
                         /** Issue #226: the level now and after, the points the next update makes, and the points an update that would hold it (-1: at the ceiling, no number holds it). */
                         double educationNow, double educationAfter, double educationMade, double educationToHold) {}

    public static Result of(World w, GameConfig cfg, int countryId, long seed) {
        Commodities com = Commodities.of(cfg);
        UpdateResult r = Update.run(w, cfg, seed);
        Country before = w.country(countryId), after = r.next().country(countryId);
        double civ0 = 0, civ1 = 0, food0 = 0, food1 = 0;
        for (Sector s : w.sectors()) if (s.owner() == countryId) { civ0 += s.stock().get(com.civ); food0 += s.stock().get(com.food); }
        for (Sector s : r.next().sectors()) if (s.owner() == countryId) { civ1 += s.stock().get(com.civ); food1 += s.stock().get(com.food); }
        List<Trouble> starving = new ArrayList<>(), spoiling = new ArrayList<>();
        int done = 0, held = 0;
        for (Event e : r.events()) {
            if (e.country() != countryId || e.at() == null) continue;
            if (e.type().equals("starvation")) starving.add(trouble(w, com, before, e, true));
            else if (e.type().equals("spoilage")) spoiling.add(trouble(w, com, before, e, false));
        }
        for (Flow f : r.flows()) if (f.owner() == countryId) { if (f.completed()) done++; else held++; }
        starving.sort(Comparator.comparingDouble((Trouble t) -> -t.amount()));
        spoiling.sort(Comparator.comparingDouble((Trouble t) -> -t.amount()));
        var lv = cfg.economy().levels();
        double e0 = before.levels().education(), e1 = after.levels().education();
        return new Result(w.updateNumber() + 1, before.cash(), after.cash(), civ0, civ1, food0, food1, before.btu(), after.btu(),
                starving.size(), spoiling.size(), done, held, List.copyOf(starving), List.copyOf(spoiling),
                e0, e1, finite(lv.educationMade(e0, e1, civ1, cfg.etus())), finite(lv.educationToHold(e0, civ1, cfg.etus())));
    }

    /** JSON has no infinity: a level at the 250 ceiling cannot be held by any number of points, and says so as -1. */
    private static double finite(double d) {
        return Double.isFinite(d) ? d : -1;
    }

    /** The sector as it stands now — what it has and whether anything is set to bring food in — is the best hint at why. */
    private static Trouble trouble(World w, Commodities com, Country c, Event e, boolean starvation) {
        Sector s = w.sector(e.at());
        Coord rel = CountryView.relative(w, c.capital(), e.at());
        double food = s.stock().get(com.food), people = s.stock().get(com.civ) + s.stock().get(com.mil) + s.stock().get(com.uw);
        String hint;
        if (starvation) {
            String supply = s.distCenter() == null ? "no distribution centre" : !s.hasThreshold(com.food) ? "no food threshold" : "food threshold " + Math.round(s.threshold(com.food));
            hint = "food " + Math.round(food) + " for " + Math.round(people) + " people; " + supply;
        } else {
            hint = "food " + Math.round(food) + " on hand; " + (s.distCenter() == null ? "no distribution centre to send it to" : "surplus above threshold is not leaving fast enough");
        }
        return new Trouble(rel, s.designation(), e.amount(), hint);
    }
}
