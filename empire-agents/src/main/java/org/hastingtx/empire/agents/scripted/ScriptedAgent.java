package org.hastingtx.empire.agents.scripted;

import org.hastingtx.empire.agent.AgentController;
import org.hastingtx.empire.agent.TurnContext;
import org.hastingtx.empire.agent.TurnResult;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.engine.view.CountryView.SectorView;

import java.util.*;

/**
 * A fixed opening book. Deterministic, fast, and deliberately dumb: it exists to exercise
 * the engine, not to win. The default for regression tests and the balance harness.
 *
 * <p>Each turn: break sanctuary; designate anything undesignated; wire every sector's
 * distribution to the capital with a standard threshold set; explore outward from any
 * sector that can spare the people and the mobility, while keeping a BTU reserve.
 */
public final class ScriptedAgent implements AgentController {
    private final OpeningBook book;

    public ScriptedAgent() { this(OpeningBook.DEFAULT); }
    public ScriptedAgent(OpeningBook book) { this.book = book; }

    @Override
    public TurnResult turn(TurnContext ctx) {
        CountryView v = ctx.view();
        List<Command> out = new ArrayList<>();
        double btu = v.btu();
        var costs = ctx.rules().economy().btu();

        if (v.inSanctuary()) { out.add(new Command.BreakSanctuary()); btu -= costs.cost("break_sanctuary"); }

        Map<Coord, SectorView> owned = new TreeMap<>();
        Set<Coord> unowned = new TreeSet<>();
        for (SectorView s : v.sectors()) {
            if (s.full()) owned.put(s.at(), s);
            else if (s.owner() == Sector.NOBODY && !s.terrain().equals("ocean")) unowned.add(s.at());
        }

        for (SectorView s : owned.values()) {
            if (btu < book.btuReserve()) break;
            boolean isCapital = s.at().equals(v.capital());
            if (s.designation().equals("wilderness")) {
                String d = book.chooseDesignation(s, v);
                out.add(new Command.Designate(s.at(), d)); btu -= costs.cost("designate");
            }
            if (!isCapital && s.distCenter() == null) {
                out.add(new Command.Distribute(s.at(), v.capital())); btu -= costs.cost("distribute");
                for (var t : book.sectorThresholds(s, v).entrySet()) { out.add(new Command.Threshold(s.at(), t.getKey(), t.getValue())); btu -= costs.cost("threshold"); }
            }
            if (isCapital && s.thresholds().isEmpty()) {
                for (var t : book.capitalThresholds(s, v).entrySet()) { out.add(new Command.Threshold(s.at(), t.getKey(), t.getValue())); btu -= costs.cost("threshold"); }
            }
        }

        // explore: from each sector with spare people, into the first adjacent unowned land sector
        int explores = 0;
        Set<Coord> claimed = new HashSet<>();
        for (SectorView s : owned.values()) {
            if (explores >= book.maxExploresPerTurn() || btu < book.btuReserve() + costs.cost("explore")) break;
            double civ = s.stock().getOrDefault("civ", 0.0);
            if (civ < book.exploreMinCiv() || s.mobility() < book.exploreMinMobility()) continue;
            for (Coord n : neighboursOf(s.at(), v)) {
                if (!unowned.contains(n) || claimed.contains(n)) continue;
                out.add(new Command.Explore(s.at(), n, book.exploreCivs()));
                btu -= costs.cost("explore"); explores++; claimed.add(n);
                break;
            }
        }
        return new TurnResult(out, "");
    }

    /** Neighbours by hex geometry, restricted to what the view contains. */
    private static List<Coord> neighboursOf(Coord c, CountryView v) {
        Set<Coord> known = new HashSet<>();
        for (SectorView s : v.sectors()) known.add(s.at());
        List<Coord> out = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            Coord n = org.hastingtx.empire.engine.geo.Hex.stepRaw(c, d);
            if (known.contains(n)) out.add(n);
        }
        return out;
    }
}
