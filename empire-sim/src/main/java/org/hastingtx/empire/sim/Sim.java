package org.hastingtx.empire.sim;

import org.hastingtx.empire.agent.AgentController;
import org.hastingtx.empire.agent.AgentTurnLog;
import org.hastingtx.empire.agent.TurnContext;
import org.hastingtx.empire.agent.TurnResult;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.command.CommandResult;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Event;
import org.hastingtx.empire.engine.update.Flow;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.hastingtx.empire.engine.view.CountryView;

import java.util.*;
import java.util.function.IntFunction;

/**
 * Headless game loop: agents act, the update runs, repeat. Everything an integration test
 * or the balance harness needs, with no I/O. Per-update seeds derive from the run seed.
 */
public final class Sim {
    public record Row(long update, int country, String name, int sectors, double civ, double mil, double uw, double food, double iron, double lcm,
                      double cash, double btu, double effSum, double tech, double research, double education, double happiness,
                      int heldParcels, int flowsCompleted, int flowsHeld, int cmdsAccepted, int cmdsRejected, double score) {}

    public record Result(World world, List<Row> rows, List<String> hashes, List<Event> events, List<AgentTurnLog> turns, List<Flow> lastFlows) {}

    private final GameConfig cfg;
    private final Commodities com;
    private final CommandExecutor exec;

    public Sim(GameConfig cfg) { this.cfg = cfg; this.com = Commodities.of(cfg); this.exec = new CommandExecutor(cfg); }

    public World newWorld(List<String> names, long seed) { return new WorldGenerator(cfg).generate(names, seed); }

    public Result run(World start, int updates, long seed, IntFunction<AgentController> agents) {
        World w = start;
        List<Row> rows = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        List<AgentTurnLog> turns = new ArrayList<>();
        Map<Integer, List<String>> lastErrors = new HashMap<>();
        Map<Integer, String> scratch = new HashMap<>();
        List<Event> since = new ArrayList<>();
        List<Flow> lastFlows = List.of();
        int[] accepted = new int[w.countries().size()], rejected = new int[w.countries().size()];

        for (int u = 1; u <= updates; u++) {
            Arrays.fill(accepted, 0); Arrays.fill(rejected, 0);
            for (Country c : w.countries()) {
                AgentController agent = agents.apply(c.id());
                if (agent == null) continue;
                CountryView view = CountryView.of(w, cfg, c.id());
                long t0 = System.nanoTime();
                TurnResult tr;
                try { tr = agent.turn(new TurnContext(view, cfg, List.copyOf(since), lastErrors.getOrDefault(c.id(), List.of()), scratch.getOrDefault(c.id(), ""))); }
                catch (RuntimeException e) { tr = TurnResult.none(); lastErrors.put(c.id(), List.of("agent threw: " + e)); }
                List<String> errs = new ArrayList<>();
                double btuSpent = 0;
                for (Command cmd : tr.commands()) {
                    CommandResult r = exec.execute(w, c.id(), cmd);
                    if (r.ok()) { w = r.world(); accepted[c.id()]++; btuSpent += r.btuSpent(); }
                    else { rejected[c.id()]++; errs.add(cmd.verb() + ": " + r.error()); }
                }
                lastErrors.put(c.id(), errs);
                scratch.put(c.id(), tr.scratchpad() == null ? "" : tr.scratchpad());
                turns.add(new AgentTurnLog(c.id(), w.updateNumber(), agent.getClass().getSimpleName(), tr.commands().size(), accepted[c.id()], errs, btuSpent, (System.nanoTime() - t0) / 1_000_000));
            }
            UpdateResult ur = Update.run(w, cfg, seed * 1_000_003L + u);
            w = ur.next();
            hashes.add(ur.stateHash());
            events.addAll(ur.events());
            since = new ArrayList<>(ur.events());
            lastFlows = ur.flows();
            rows.addAll(rows(w, ur, accepted, rejected));
        }
        return new Result(w, rows, hashes, events, turns, lastFlows);
    }

    /**
     * A row per country, from one pass over the sectors rather than one pass per country.
     *
     * <p>This was {@code row(w, c, ...)} called for each country, and each call walked the whole world
     * — plus {@link Scoring#score} walking it again. At forty countries on a 512x1024 map that is
     * eighty scans of half a million sectors an update, which was several seconds and, once the update
     * itself came down (issues #87, #89), most of what a sim cycle cost. It is harness bookkeeping, not
     * a rule, and it now costs one scan.
     */
    private List<Row> rows(World w, UpdateResult ur, int[] acc, int[] rej) {
        int n = w.countries().size();
        int[] sectors = new int[n], held = new int[n];
        double[] civ = new double[n], mil = new double[n], uw = new double[n], food = new double[n],
                 iron = new double[n], lcm = new double[n], eff = new double[n];
        int iIron = com.has("iron") ? com.index("iron") : -1, iLcm = com.has("lcm") ? com.index("lcm") : -1;
        for (Sector s : w.sectors()) {
            int o = s.owner();
            if (o < 0) continue;
            sectors[o]++; held[o] += s.held().size(); eff[o] += s.efficiency();
            civ[o] += s.stock().get(com.civ); mil[o] += s.stock().get(com.mil); uw[o] += s.stock().get(com.uw); food[o] += s.stock().get(com.food);
            if (iIron >= 0) iron[o] += s.stock().get(iIron);
            if (iLcm >= 0) lcm[o] += s.stock().get(iLcm);
        }
        int[] done = new int[n], holds = new int[n];
        for (Flow f : ur.flows()) if (f.owner() >= 0) { if (f.completed()) done[f.owner()]++; else holds[f.owner()]++; }

        List<Row> out = new ArrayList<>(n);
        for (Country c : w.countries()) {
            int i = c.id();
            out.add(new Row(w.updateNumber(), i, c.name(), sectors[i], civ[i], mil[i], uw[i], food[i], iron[i], lcm[i], c.cash(), c.btu(), eff[i],
                    c.levels().tech(), c.levels().research(), c.levels().education(), c.levels().happiness(), held[i], done[i], holds[i], acc[i], rej[i],
                    Scoring.score(cfg, c, sectors[i], civ[i], eff[i])));
        }
        return out;
    }
}
