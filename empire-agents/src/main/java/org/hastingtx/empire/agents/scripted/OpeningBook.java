package org.hastingtx.empire.agents.scripted;

import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.engine.view.CountryView.SectorView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The scripted agent's playbook. Everything the agent decides is a method here so a
 * different book is a different agent without touching the loop.
 *
 * <p>TODO(Richard): {@link #chooseDesignation} is the heart of it and is yours to write.
 * The default below is a placeholder so the harness runs. See the session notes for the
 * trade-offs (fertility vs minerals, when to add manufacturing, what the capital needs).
 */
public interface OpeningBook {

    /** Which designation a freshly explored sector gets. Must be a sector-type id from config. */
    String chooseDesignation(SectorView sector, CountryView country);

    /** Thresholds for an ordinary sector distributing to the capital. commodity id -> amount. */
    Map<String, Double> sectorThresholds(SectorView sector, CountryView country);

    /** Thresholds for the capital itself (what it keeps before exporting). */
    Map<String, Double> capitalThresholds(SectorView capital, CountryView country);

    default double btuReserve() { return 20; }
    default int maxExploresPerTurn() { return 3; }
    default double exploreMinCiv() { return 80; }
    default double exploreMinMobility() { return 10; }
    default double exploreCivs() { return 20; }

    OpeningBook DEFAULT = new OpeningBook() {
        // PLACEHOLDER — Richard's version replaces this. Farms where it is fertile, mines where
        // it is not, and every fourth sector a light-manufacturing plant so the iron goes somewhere.
        @Override public String chooseDesignation(SectorView s, CountryView v) {
            int owned = 0;
            for (SectorView o : v.sectors()) if (o.full() && !o.designation().equals("wilderness")) owned++;
            if (owned > 0 && owned % 4 == 3) return "light_manufacturing";
            var r = s.resources();
            if (r == null) return "agribusiness";
            return r.fertility() >= r.minerals() ? "agribusiness" : "mine";
        }
        @Override public Map<String, Double> sectorThresholds(SectorView s, CountryView v) {
            Map<String, Double> t = new LinkedHashMap<>();
            t.put("civ", 150.0);     // pull settlers from the capital
            t.put("food", 100.0);    // keep a buffer, push the surplus home
            t.put("iron", 0.0);      // ore goes home
            t.put("lcm", s.designation().equals("light_manufacturing") ? 0.0 : 10.0);
            return t;
        }
        @Override public Map<String, Double> capitalThresholds(SectorView s, CountryView v) {
            Map<String, Double> t = new LinkedHashMap<>();
            t.put("civ", 300.0);
            t.put("food", 600.0);
            return t;
        }
    };
}
