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
 * <p>Each turn, in a fixed order: break sanctuary; designate anything undesignated; wire every
 * sector's distribution to the capital with a standard threshold set; put a standing delivery order
 * on a producer; pave roads and, once tech allows, lay rail; make a harbour, build a hull in it and
 * send it fishing; then explore outward from any sector that can spare the people and the mobility,
 * keeping a BTU reserve throughout.
 *
 * <p>The sea and the rails are here for <b>coverage</b>, not for advantage (issue #73). A harness
 * run that never builds a ship never exercises fuel, crews, harbours or the fishing mission, and a
 * conservation bug in any of them goes unnoticed until a person hits it. Each of those steps does
 * the least that reaches the feature and then stops. Nobody tunes strategy in here.
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

        // a standing delivery order on one producer, so the deliver path runs every update
        for (SectorView s2 : owned.values()) {
            if (btu < book.btuReserve() + costs.cost("deliver")) break;
            if (s2.at().equals(v.capital()) || !s2.deliveries().isEmpty()) continue;
            if (!s2.designation().equals("agribusiness")) continue;
            out.add(new Command.Deliver(s2.at(), book.deliverCommodity(), book.deliverDirection(), book.deliverThreshold()));
            btu -= costs.cost("deliver");
            break;
        }

        // roads, and rails once the country's tech reaches them
        if (book.roadTarget() > 0) {
            for (SectorView s2 : owned.values()) {
                if (btu < book.btuReserve() + costs.cost("build_road")) break;
                if (s2.roadTarget() > 0 || s2.roadLevel() >= book.roadTarget()) continue;
                out.add(new Command.BuildRoad(s2.at(), book.roadTarget()));
                btu -= costs.cost("build_road");
                break;
            }
        }
        if (book.railTarget() > 0 && v.levels().tech() >= ctx.rules().infrastructure().rail().techRequired()) {
            for (SectorView s2 : owned.values()) {
                if (btu < book.btuReserve() + costs.cost("build_rail")) break;
                if (s2.railTarget() > 0 || s2.railLevel() >= book.railTarget()) continue;
                out.add(new Command.BuildRail(s2.at(), book.railTarget()));
                btu -= costs.cost("build_rail");
                break;
            }
        }

        // the sea: a harbour, a hull in it, and the hull sent fishing
        var ships = ctx.rules().units() == null ? null : ctx.rules().units().ships();
        if (ships != null && ctx.rules().units().enabled()) {
            List<SectorView> harbors = new ArrayList<>();
            for (SectorView s2 : owned.values()) if (s2.designation().equals("harbor")) harbors.add(s2);

            if (harbors.size() < book.harborsWanted() && btu >= book.btuReserve() + costs.cost("designate")) {
                for (SectorView s2 : owned.values()) {
                    if (s2.at().equals(v.capital()) || s2.designation().equals("harbor")) continue;
                    if (!touchesOcean(s2.at(), v)) continue;
                    out.add(new Command.Designate(s2.at(), "harbor"));
                    btu -= costs.cost("designate");
                    break;
                }
            }

            var want = classOf(ctx, book.shipClass());

            // A sector's thresholds are set once, when it is first wired to distribution — so a sector
            // that becomes a harbour afterwards is still exporting its materials under the old rule and
            // can never afford a hull. Raise the ones a hull needs, once, when the harbour appears.
            if (want != null && want.build() != null) {
                for (SectorView h : harbors) {
                    if (btu < book.btuReserve() + costs.cost("threshold")) break;
                    for (var need : want.build().entrySet()) {
                        if (need.getKey().equals("cash")) continue;
                        if (h.thresholds().getOrDefault(need.getKey(), 0.0) >= need.getValue()) continue;
                        out.add(new Command.Threshold(h.at(), need.getKey(), need.getValue() * 2));
                        btu -= costs.cost("threshold");
                    }
                }
            }

            var cls = want;
            if (cls != null && v.levels().tech() >= cls.techRequired() && v.ships().size() < book.shipsWanted()) {
                for (SectorView h : harbors) {
                    if (btu < book.btuReserve() + costs.cost("build_ship")) break;
                    if (h.efficiency() < ships.harborMinEfficiency()) continue;
                    // only ask for what the harbour can actually pay for: a build it cannot afford is
                    // rejected, and an agent that reissues it every update fills the log with noise
                    if (!canAfford(h, cls.build())) continue;
                    out.add(new Command.BuildShip(h.at(), cls.id(), null));
                    btu -= costs.cost("build_ship");
                    break;
                }
            }

            // any hull that can fish and is not fishing gets sent out; its harbour is its home
            for (var sh : v.ships()) {
                if (btu < book.btuReserve() + costs.cost("fish")) break;
                var c = classOf(ctx, sh.cls());
                // the class's ROLE is "fishing"; the mission a ship is on is Ship.FISH ("fish").
                // Comparing the wrong one of those means the order is reissued every single update.
                if (c == null || !"fishing".equals(c.role())) continue;
                if (org.hastingtx.empire.engine.model.Ship.FISH.equals(sh.mission())) continue;
                out.add(new Command.Fish(sh.id(), sh.at(), false));
                btu -= costs.cost("fish");
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

    /** Whether a sector's own stock covers a cost table. Cash is the country's, not the sector's. */
    private static boolean canAfford(SectorView s, Map<String, Double> cost) {
        if (cost == null) return true;
        for (var e : cost.entrySet()) {
            if (e.getKey().equals("cash")) continue;
            if (s.stock().getOrDefault(e.getKey(), 0.0) < e.getValue()) return false;
        }
        return true;
    }

    /** A land sector with open water beside it: where a harbour can go. */
    private static boolean touchesOcean(Coord c, CountryView v) {
        Map<Coord, String> terrain = new HashMap<>();
        for (SectorView s : v.sectors()) terrain.put(s.at(), s.terrain());
        for (int d = 0; d < 6; d++)
            if ("ocean".equals(terrain.get(org.hastingtx.empire.engine.geo.Hex.stepRaw(c, d)))) return true;
        return false;
    }

    private static org.hastingtx.empire.engine.config.UnitsCfg.ShipClassCfg classOf(TurnContext ctx, String id) {
        var ships = ctx.rules().units() == null ? null : ctx.rules().units().ships();
        if (ships == null || ships.classes() == null) return null;
        for (var c : ships.classes()) if (c.id().equals(id)) return c;
        return null;
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
