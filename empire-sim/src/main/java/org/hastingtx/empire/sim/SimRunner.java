package org.hastingtx.empire.sim;

import org.hastingtx.empire.agents.scripted.ScriptedAgent;
import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Event;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CLI: run N updates of a preset with a roster of scripted agents, write per-update CSV,
 * the event log and the final state hash.
 *
 * <pre>java -jar empire-sim.jar --preset teaching --updates 60 --countries 4 --seed 1 --out sim-out</pre>
 */
public final class SimRunner {
    public static void main(String[] args) throws IOException {
        Map<String, String> a = parse(args);
        String preset = a.getOrDefault("preset", "teaching");
        int updates = Integer.parseInt(a.getOrDefault("updates", "60"));
        int countries = Integer.parseInt(a.getOrDefault("countries", "4"));
        long seed = Long.parseLong(a.getOrDefault("seed", "1"));
        Path out = Path.of(a.getOrDefault("out", "sim-out"));

        ConfigLoader.Loaded loaded = a.containsKey("config") ? new ConfigLoader().loadWithHash(Path.of(a.get("config"))) : new ConfigLoader().loadPreset(preset);
        GameConfig cfg = loaded.config();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < countries; i++) names.add("C" + (i + 1));

        Sim sim = new Sim(cfg);
        World w = sim.newWorld(names, seed);
        long t0 = System.nanoTime();
        Sim.Result r = sim.run(w, updates, seed, id -> new ScriptedAgent());
        long ms = (System.nanoTime() - t0) / 1_000_000;

        Files.createDirectories(out);
        writeCsv(out.resolve("updates.csv"), r.rows());
        writeEvents(out.resolve("events.log"), r.events());
        Files.writeString(out.resolve("hash.txt"), r.hashes().get(r.hashes().size() - 1) + "\n");
        Files.writeString(out.resolve("run.txt"), "preset=" + preset + " config_hash=" + loaded.hash() + " updates=" + updates + " countries=" + countries + " seed=" + seed + " ms=" + ms + "\n");
        printTable(System.out, r, updates, ms);
    }

    static void writeCsv(Path p, List<Sim.Row> rows) throws IOException {
        StringBuilder sb = new StringBuilder("update,country,name,sectors,civ,mil,uw,food,iron,lcm,cash,btu,eff_sum,tech,research,education,happiness,held,flows_done,flows_held,cmds_ok,cmds_rejected,score\n");
        for (Sim.Row r : rows) sb.append(String.format(Locale.ROOT, "%d,%d,%s,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%.1f%n",
                r.update(), r.country(), r.name(), r.sectors(), r.civ(), r.mil(), r.uw(), r.food(), r.iron(), r.lcm(), r.cash(), r.btu(), r.effSum(),
                r.tech(), r.research(), r.education(), r.happiness(), r.heldParcels(), r.flowsCompleted(), r.flowsHeld(), r.cmdsAccepted(), r.cmdsRejected(), r.score()));
        Files.writeString(p, sb.toString());
    }

    static void writeEvents(Path p, List<Event> events) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Event e : events) sb.append(e.type()).append('\t').append(e.country()).append('\t').append(e.at()).append('\t').append(e.message()).append('\t').append(String.format(Locale.ROOT, "%.2f", e.amount())).append('\n');
        Files.writeString(p, sb.toString());
    }

    static void printTable(PrintStream out, Sim.Result r, int updates, long ms) {
        out.printf(Locale.ROOT, "%d updates in %d ms  final hash %s%n", updates, ms, r.hashes().get(r.hashes().size() - 1));
        out.printf(Locale.ROOT, "%-4s %-6s %7s %9s %9s %9s %9s %9s %8s %6s %8s%n", "id", "name", "sectors", "civ", "food", "iron", "lcm", "cash", "eff", "held", "score");
        for (Sim.Row row : r.rows()) {
            if (row.update() != updates) continue;
            out.printf(Locale.ROOT, "%-4d %-6s %7d %9.0f %9.0f %9.0f %9.0f %9.0f %8.0f %6d %8.0f%n", row.country(), row.name(), row.sectors(), row.civ(), row.food(), row.iron(), row.lcm(), row.cash(), row.effSum(), row.heldParcels(), row.score());
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (Event e : r.events()) counts.merge(e.type(), 1, Integer::sum);
        out.println("events: " + counts);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) if (args[i].startsWith("--") && i + 1 < args.length) m.put(args[i].substring(2), args[++i]);
        return m;
    }
}
