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
 *
 * <p>{@code --width}, {@code --height} and {@code --water} override the preset's map, the same way game
 * creation does (issue #81), and {@code --mem} reports what the world actually weighs after a full GC
 * (issue #77). That is how the footprint numbers in {@code docs/progress.md} are taken: they are
 * measured on a real world of a stated size, not computed from a table of field widths.
 */
public final class SimRunner {
    public static void main(String[] args) throws IOException {
        Map<String, String> a = parse(args);
        String preset = a.getOrDefault("preset", "teaching");
        int updates = Integer.parseInt(a.getOrDefault("updates", "60"));
        int countries = Integer.parseInt(a.getOrDefault("countries", "4"));
        long seed = Long.parseLong(a.getOrDefault("seed", "1"));
        Path out = Path.of(a.getOrDefault("out", "sim-out"));
        boolean mem = a.containsKey("mem");

        ConfigLoader ldr = new ConfigLoader();
        ConfigLoader.Loaded loaded = a.containsKey("config") ? ldr.loadWithHash(Path.of(a.get("config"))) : ldr.loadPreset(preset);
        loaded = withMap(ldr, loaded, a.get("width"), a.get("height"), a.get("water"));
        GameConfig cfg = loaded.config();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < countries; i++) names.add("C" + (i + 1));

        Sim sim = new Sim(cfg);
        World w = sim.newWorld(names, seed);
        if (mem) reportWorld("generated", w);
        long t0 = System.nanoTime();
        Sim.Result r = sim.run(w, updates, seed, id -> new ScriptedAgent());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (mem) reportWorld("after " + updates + " update(s)", r.world());

        Files.createDirectories(out);
        writeCsv(out.resolve("updates.csv"), r.rows());
        writeEvents(out.resolve("events.log"), r.events());
        Files.writeString(out.resolve("hash.txt"), r.hashes().get(r.hashes().size() - 1) + "\n");
        Files.writeString(out.resolve("run.txt"), "preset=" + preset + " config_hash=" + loaded.hash() + " updates=" + updates + " countries=" + countries + " seed=" + seed + " ms=" + ms + "\n");
        printTable(System.out, r, updates, ms);
    }

    /** Override the preset's map, as game creation does. Nulls keep the preset's value. */
    static ConfigLoader.Loaded withMap(ConfigLoader ldr, ConfigLoader.Loaded l, String width, String height, String water) {
        if (width == null && height == null && water == null) return l;
        Map<String, Object> raw = new LinkedHashMap<>(l.raw());
        @SuppressWarnings("unchecked") Map<String, Object> world = new LinkedHashMap<>((Map<String, Object>) raw.getOrDefault("world", Map.of()));
        if (width != null) world.put("width", Integer.parseInt(width));
        if (height != null) world.put("height", Integer.parseInt(height));
        if (water != null) {
            @SuppressWarnings("unchecked") Map<String, Object> terrain = new LinkedHashMap<>((Map<String, Object>) world.getOrDefault("terrain", Map.of()));
            terrain.put("land_fraction", Math.round((1.0 - Double.parseDouble(water) / 100.0) * 1000.0) / 1000.0);
            world.put("terrain", terrain);
        }
        raw.put("world", world);
        return ldr.loadYaml(ldr.toYaml(raw));
    }

    /**
     * What the world weighs, live. Drop every other reference, collect twice — the second pass clears
     * what the first pass's finalisation and reference processing freed — and read the difference
     * between the heap in use and the heap in use with nothing but this world in it. It is a
     * whole-heap measurement, so it includes the sectors, the ships and the country list, which is the
     * number that matters when deciding whether a map size fits.
     */
    static void reportWorld(String when, World w) {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        long used = rt.totalMemory() - rt.freeMemory();
        long sectors = (long) w.width() * w.height();
        System.out.printf(Locale.ROOT, "mem %-24s %d x %d = %,d sectors, heap in use %,d MB, %.0f B/sector%n",
                when, w.width(), w.height(), sectors, used / (1024 * 1024), (double) used / sectors);
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
