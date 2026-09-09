package org.hastingtx.empire.server.macro;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.engine.view.CountryView.SectorView;
import org.hastingtx.empire.server.console.SectorSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A macro step is a panel command with the sector left blank:
 * {@code {verb, commodity?, amount?, clear?, type?, direction?, center?}} where {@code center} is
 * {@code "capital"} or {@code {dx, dy}} relative to the capital (for distribute). Expanding a macro
 * over sectors yields ordinary commands — every rule and BTU charge stays in the executor. Issue #47.
 */
public final class Macros {
    private Macros() {}

    public static final int SLOTS = 10;
    public static final List<String> VERBS = List.of("threshold", "distribute", "deliver", "build_road", "build_rail", "designate");

    /** The commands for one sector, in step order. */
    public static List<Command> expand(List<Map<String, Object>> steps, CountryView v, GameConfig cfg, Coord at, boolean mixed) {
        List<Command> out = new ArrayList<>();
        for (Map<String, Object> s : steps) {
            String verb = str(s, "verb");
            if (verb == null || !VERBS.contains(verb)) throw new IllegalArgumentException("a macro cannot " + verb);
            String commodity = str(s, "commodity");
            boolean clear = Boolean.TRUE.equals(s.get("clear"));
            double amount = num(s, "amount");
            switch (verb) {
                case "threshold" -> out.add(new Command.Threshold(at, commodity, clear ? -1 : mixed ? SectorSelector.massThreshold(v, cfg, at, commodity, amount) : amount));
                case "distribute" -> out.add(new Command.Distribute(at, clear ? null : centre(s.get("center"), v)));
                case "deliver" -> {
                    String dir = str(s, "direction");
                    int d = clear || dir == null || dir.equalsIgnoreCase("none") ? -1 : Hex.parseDir(dir);
                    if (!clear && d < 0) throw new IllegalArgumentException("bad direction in macro: " + dir);
                    out.add(new Command.Deliver(at, commodity, d < 0 ? null : d, amount));
                }
                case "build_road" -> out.add(new Command.BuildRoad(at, amount));
                case "build_rail" -> out.add(new Command.BuildRail(at, amount));
                case "designate" -> out.add(new Command.Designate(at, str(s, "type")));
                default -> throw new IllegalArgumentException("a macro cannot " + verb);
            }
        }
        return out;
    }

    /** One sentence per step, for the console listing and replies. */
    public static String describe(Map<String, Object> s) {
        String verb = str(s, "verb");
        boolean clear = Boolean.TRUE.equals(s.get("clear"));
        return switch (verb == null ? "" : verb) {
            case "threshold" -> clear ? "clear threshold " + str(s, "commodity") : "threshold " + str(s, "commodity") + " " + q(num(s, "amount"));
            case "distribute" -> clear ? "clear distribution centre" : "distribution centre: " + centreText(s.get("center"));
            case "deliver" -> clear ? "stop delivering " + str(s, "commodity") : "deliver " + str(s, "commodity") + " " + str(s, "direction") + " above " + q(num(s, "amount"));
            case "build_road" -> num(s, "amount") <= 0 ? "cancel road order" : "road toward " + q(num(s, "amount"));
            case "build_rail" -> num(s, "amount") <= 0 ? "cancel rail order" : "rail toward " + q(num(s, "amount"));
            case "designate" -> "designate " + str(s, "type");
            default -> "? " + verb;
        };
    }

    public static String describe(List<Map<String, Object>> steps) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> s : steps) sb.append(sb.isEmpty() ? "" : "; ").append(describe(s));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Coord centre(Object c, CountryView v) {
        if (c == null || "capital".equals(c)) return v.capital();
        if (c instanceof Map<?, ?> m) {
            int dx = (int) num((Map<String, Object>) m, "dx"), dy = (int) num((Map<String, Object>) m, "dy");
            for (SectorView s : v.sectors()) if (s.relative().x() == dx && s.relative().y() == dy) return s.at();
            return new Coord(v.capital().x() + dx, v.capital().y() + dy);
        }
        throw new IllegalArgumentException("bad centre in macro");
    }

    @SuppressWarnings("unchecked")
    private static String centreText(Object c) {
        if (c == null || "capital".equals(c)) return "capital";
        if (c instanceof Map<?, ?> m) return q(num((Map<String, Object>) m, "dx")) + "," + q(num((Map<String, Object>) m, "dy")) + " from the capital";
        return "?";
    }

    private static String str(Map<String, Object> m, String k) { Object o = m.get(k); return o == null ? null : o.toString(); }
    private static double num(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof Number n ? n.doubleValue() : 0; }
    private static String q(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    /** Validate a macro before saving: known verbs only, at most 50 steps. */
    public static void validate(String name, List<Map<String, Object>> steps) {
        if (name == null || name.isBlank() || name.length() > 40) throw new IllegalArgumentException("a macro needs a name of up to 40 characters");
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("a macro needs at least one step");
        if (steps.size() > 50) throw new IllegalArgumentException("a macro can have at most 50 steps");
        for (Map<String, Object> s : steps) { String v = str(s, "verb"); if (v == null || !VERBS.contains(v)) throw new IllegalArgumentException("a macro cannot " + v); }
    }
}
