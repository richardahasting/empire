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
            for (Country c : w.countries()) rows.add(row(w, c, ur, accepted[c.id()], rejected[c.id()]));
        }
        return new Result(w, rows, hashes, events, turns, lastFlows);
    }

    private Row row(World w, Country c, UpdateResult ur, int acc, int rej) {
        int sectors = 0, held = 0; double civ = 0, mil = 0, uw = 0, food = 0, iron = 0, lcm = 0, eff = 0;
        int iIron = com.has("iron") ? com.index("iron") : -1, iLcm = com.has("lcm") ? com.index("lcm") : -1;
        for (Sector s : w.sectors()) {
            if (s.owner() != c.id()) continue;
            sectors++; held += s.held().size(); eff += s.efficiency();
            civ += s.stock().get(com.civ); mil += s.stock().get(com.mil); uw += s.stock().get(com.uw); food += s.stock().get(com.food);
            if (iIron >= 0) iron += s.stock().get(iIron);
            if (iLcm >= 0) lcm += s.stock().get(iLcm);
        }
        int done = 0, holds = 0;
        for (Flow f : ur.flows()) if (f.owner() == c.id()) { if (f.completed()) done++; else holds++; }
        return new Row(w.updateNumber(), c.id(), c.name(), sectors, civ, mil, uw, food, iron, lcm, c.cash(), c.btu(), eff,
                c.levels().tech(), c.levels().research(), c.levels().education(), c.levels().happiness(), held, done, holds, acc, rej,
                Scoring.score(cfg, w, c));
    }
}
