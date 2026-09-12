package org.hastingtx.empire.agents.scripted;

import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.engine.view.CountryView.SectorView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The scripted agent's playbook. Everything the agent decides is a method here so a
 * different book is a different agent without touching the loop.
 *
 * <p>This is a TEST FIXTURE, not a player. It is deliberately dumb and must stay
 * deterministic: golden hashes and the balance harness depend on it doing the same thing
 * every run. Agents that are meant to play well learn through play (LlmAgent, M3); nobody
 * hand-tunes strategy in here. Richard, 2026-09-07.
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

    // ---- coverage knobs (issue #73) ---------------------------------------------------------
    // These exist so a sim run touches the sea and the rails at all, not so the agent plays better.
    // Every one is a coverage decision: reach the feature, exercise it, stop. Turning any of them
    // off is how a golden-hash run pins the old behaviour.

    /** Designate this many coastal sectors as harbours, so ships have somewhere to be built. */
    default int harborsWanted() { return 1; }

    /** Which hull to lay. Skipped entirely when the game has units off or the tech is not there. */
    default String shipClass() { return "fishing_boat"; }

    /** How many hulls to keep. A fishing boat that is built and sent out exercises most of the sea. */
    default int shipsWanted() { return 1; }

    /** Pave owned sectors toward this road level; 0 leaves roads alone. */
    default double roadTarget() { return 20; }

    /** Lay rail toward this level once the country's tech allows it; 0 leaves rail alone. */
    default double railTarget() { return 20; }

    /** A standing delivery order for a producing sector: commodity, direction, and the threshold. */
    default String deliverCommodity() { return "food"; }
    default int deliverDirection() { return 0; }
    default double deliverThreshold() { return 400; }

    OpeningBook DEFAULT = new OpeningBook() {
        // Farms where it is fertile, mines where it is not, and every fourth sector a
        // light-manufacturing plant so the iron goes somewhere. Good enough to exercise the engine.
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
            // a harbour that exports all its lcm can never lay a hull, and the sea goes untested
            t.put("lcm", s.designation().equals("light_manufacturing") ? 0.0
                    : s.designation().equals("harbor") ? 300.0 : 10.0);
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
