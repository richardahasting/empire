package org.hastingtx.empire.server.console;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.engine.view.CountryView.SectorView;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.game.GameService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The text command line. Coordinates are player-relative (capital = 0,0) as in the
 * original. Command verbs are parsed into the same Command records the panels use and go
 * through the same executor; query verbs render text.
 */
@Service
public class Console {
    private final GameService games;
    private final org.hastingtx.empire.server.macro.MacroRepository macros;
    public Console(GameService games, org.hastingtx.empire.server.macro.MacroRepository macros) { this.games = games; this.macros = macros; }

    public record Reply(String output, boolean accepted, String error, CountryView view) {}

    public Reply run(long gameId, Account a, String line) {
        String[] t = line.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) return new Reply("", true, null, null);
        CountryView v = games.view(gameId, a);
        GameConfig cfg = games.get(gameId).cfg;
        String verb = t[0].toLowerCase(Locale.ROOT);
        try {
            return switch (verb) {
                case "help", "?" -> new Reply(HELP, true, null, null);
                case "map" -> new Reply(map(v, cfg), true, null, null);
                case "census", "cen" -> new Reply(census(v), true, null, null);
                case "break" -> cmd(gameId, a, new Command.BreakSanctuary());
                case "des", "designate" -> { need(t, 3, "des SECTOR type"); yield many(gameId, a, v, cfg, t[1], at -> new Command.Designate(at, t[2])); }
                case "thresh", "threshold" -> {
                    need(t, 4, "thresh SECTOR commodity amount");
                    double n = Double.parseDouble(t[3]);
                    boolean mixed = SectorSelector.isMixed(t[1]);
                    yield many(gameId, a, v, cfg, t[1], at -> new Command.Threshold(at, t[2], mixed ? SectorSelector.massThreshold(v, cfg, at, t[2], n) : n), mixed && n >= 0 ? SectorSelector.massThresholdNote(cfg) : null);
                }
                case "dist", "distribute" -> { need(t, 3, "dist SECTOR cx,cy|none"); Coord ctr = t[2].equalsIgnoreCase("none") ? null : abs(v, t[2]); yield many(gameId, a, v, cfg, t[1], at -> new Command.Distribute(at, ctr)); }
                case "ships", "fleet" -> new Reply(fleet(v, cfg), true, null, null);
                case "contacts", "radar" -> new Reply(contacts(v), true, null, null);
                case "build" -> { need(t, 3, "build HARBOUR CLASS [name]"); yield cmd(gameId, a, new Command.BuildShip(abs(v, t[1]), t[2], t.length > 3 ? String.join(" ", Arrays.copyOfRange(t, 3, t.length)) : null)); }
                case "sail" -> { need(t, 3, "sail SHIP x,y | sail SHIP hold"); yield cmd(gameId, a, new Command.Sail(Long.parseLong(t[1].replace("#", "")), t[2].equalsIgnoreCase("hold") ? null : abs(v, t[2]))); }
                case "load" -> { need(t, 4, "load SHIP COMMODITY N"); yield cmd(gameId, a, new Command.Load(Long.parseLong(t[1].replace("#", "")), t[2], Double.parseDouble(t[3]))); }
                case "unload" -> { need(t, 4, "unload SHIP COMMODITY N"); yield cmd(gameId, a, new Command.Unload(Long.parseLong(t[1].replace("#", "")), t[2], Double.parseDouble(t[3]))); }
                case "lane" -> {
                    need(t, 3, "lane SHIP x,y x2,y2 [COMMODITY ...] | lane SHIP none");
                    long id = Long.parseLong(t[1].replace("#", ""));
                    if (t[2].equalsIgnoreCase("none")) yield cmd(gameId, a, new Command.Lane(id, null, null, List.of()));
                    need(t, 4, "lane SHIP x,y x2,y2 [COMMODITY ...]");
                    yield cmd(gameId, a, new Command.Lane(id, abs(v, t[2]), abs(v, t[3]), t.length > 4 ? List.of(Arrays.copyOfRange(t, 4, t.length)) : List.of()));
                }
                case "declare" -> {
                    need(t, 3, "declare war COUNTRY");
                    if (!t[1].equalsIgnoreCase("war")) yield new Reply("declare war COUNTRY", false, null, null);
                    int on = games.countryNamed(gameId, rest(line, 2));
                    if (on < 0) yield new Reply("no country called " + rest(line, 2) + " in this game", false, null, null);
                    yield cmd(gameId, a, new Command.DeclareWar(on));
                }
                case "peace" -> {
                    need(t, 2, "peace COUNTRY");
                    int with = games.countryNamed(gameId, rest(line, 1));
                    if (with < 0) yield new Reply("no country called " + rest(line, 1) + " in this game", false, null, null);
                    yield cmd(gameId, a, new Command.OfferPeace(with));
                }
                case "tel", "telegram" -> {
                    need(t, 3, "telegram COUNTRY \"what you want to say\"");
                    int to = games.countryNamed(gameId, t[1]);
                    if (to < 0) yield new Reply("no country called " + t[1] + " in this game", false, null, null);
                    yield cmd(gameId, a, new Command.Telegram(to, rest(line, 2)));
                }
                case "announce" -> {
                    need(t, 2, "announce \"what you want everyone to hear\"");
                    yield cmd(gameId, a, new Command.Announce(rest(line, 1)));
                }
                case "fish" -> { need(t, 2, "fish SHIP [x,y] | fish SHIP off"); long id = Long.parseLong(t[1].replace("#", "")); boolean off = t.length > 2 && t[2].equalsIgnoreCase("off"); yield cmd(gameId, a, new Command.Fish(id, !off && t.length > 2 ? abs(v, t[2]) : null, off)); }
                case "mine" -> { need(t, 2, "mine SHIP [x,y] | mine SHIP off"); long id = Long.parseLong(t[1].replace("#", "")); boolean off = t.length > 2 && t[2].equalsIgnoreCase("off"); yield cmd(gameId, a, new Command.Mine(id, !off && t.length > 2 ? abs(v, t[2]) : null, off)); }
                case "scrap" -> { need(t, 2, "scrap SHIP"); yield cmd(gameId, a, new Command.Scrap(Long.parseLong(t[1].replace("#", "")))); }
                case "macro", "macros" -> {
                    var mine = macros.list(a.id());
                    if (t.length >= 3 && t[1].equalsIgnoreCase("run")) {
                        need(t, 4, "macro run N SECTOR");
                        int slot = Integer.parseInt(t[2]);
                        var m = macros.find(a.id(), slot).orElseThrow(() -> new IllegalArgumentException("no macro in slot " + slot));
                        boolean mixed = SectorSelector.isMixed(t[3]);
                        List<Command> cmds = new ArrayList<>();
                        for (Coord at : SectorSelector.expand(v, cfg, t[3])) cmds.addAll(org.hastingtx.empire.server.macro.Macros.expand(m.steps(), v, cfg, at, mixed));
                        yield reply(games.commandAll(gameId, a, cmds, "console", "macro " + slot + " '" + m.name() + "'"));
                    }
                    if (mine.isEmpty()) yield new Reply("no macros yet — record one from a sector's right-click menu", true, null, null);
                    StringBuilder sb = new StringBuilder();
                    for (var m : mine) sb.append(String.format("%2d  %-20s %s%n", m.slot(), m.name(), org.hastingtx.empire.server.macro.Macros.describe(m.steps())));
                    yield new Reply(sb.toString(), true, null, null);
                }
                case "deliver", "del" -> {
                    need(t, 4, "deliver COMMODITY SECTOR DIR N   (DIR e ne nw w sw se, or none to clear)");
                    boolean clear = t[3].equalsIgnoreCase("none") || t[3].equalsIgnoreCase("off");
                    if (!clear) need(t, 5, "deliver COMMODITY SECTOR DIR N");
                    int dir = clear ? -1 : org.hastingtx.empire.engine.geo.Hex.parseDir(t[3]);
                    if (!clear && dir < 0) throw new IllegalArgumentException("direction is e, ne, nw, w, sw, se (or the original's j u y g b n) — got '" + t[3] + "'");
                    double thr = clear ? 0 : Double.parseDouble(t[4]);
                    yield many(gameId, a, v, cfg, t[2], at -> new Command.Deliver(at, t[1], clear ? null : dir, thr));
                }
                case "move" -> { need(t, 5, "move commodity from_x,y to_x,y qty"); yield cmd(gameId, a, new Command.Move(abs(v, t[2]), abs(v, t[3]), t[1], Double.parseDouble(t[4]))); }
                case "rail" -> { need(t, 3, "rail SECTOR LEVEL"); double lvl = Double.parseDouble(t[2]); yield many(gameId, a, v, cfg, t[1], at -> new Command.BuildRail(at, lvl)); }
                case "railship", "train" -> { need(t, 5, "railship COMMODITY from_x,y to_x,y qty"); yield cmd(gameId, a, new Command.RailShip(abs(v, t[2]), abs(v, t[3]), t[1], Double.parseDouble(t[4]))); }
                case "raillane" -> {
                    need(t, 3, "raillane x,y x2,y2 [COMMODITY ...] | raillane x,y x2,y2 none");
                    Coord from = abs(v, t[1]), to = abs(v, t[2]);
                    boolean off = t.length > 3 && t[3].equalsIgnoreCase("none");
                    yield cmd(gameId, a, new Command.RailLane(from, to, off || t.length <= 3 ? List.of() : List.of(Arrays.copyOfRange(t, 3, t.length)), off));
                }
                case "road" -> { need(t, 3, "road SECTOR LEVEL"); double lvl = Double.parseDouble(t[2]); yield many(gameId, a, v, cfg, t[1], at -> new Command.BuildRoad(at, lvl)); }
                case "expl", "explore" -> { need(t, 4, "expl from_x,y to_x,y civs"); yield cmd(gameId, a, new Command.Explore(abs(v, t[1]), abs(v, t[2]), Double.parseDouble(t[3]))); }
                default -> new Reply("", false, "unknown command '" + verb + "' (try help)", null);
            };
        } catch (IllegalArgumentException e) {
            return new Reply("", false, e.getMessage(), null);
        }
    }

    /** Everything after the first {@code n} words, unquoted: what somebody actually wants to say. */
    private static String rest(String line, int n) {
        String[] parts = line.trim().split("\\s+", n + 1);
        String body = parts.length > n ? parts[n] : "";
        body = body.strip();
        if (body.length() >= 2 && body.startsWith("\"") && body.endsWith("\"")) body = body.substring(1, body.length() - 1);
        return body;
    }

    private Reply cmd(long gameId, Account a, Command c) { return reply(games.command(gameId, a, c, "console")); }

    /** The same verb on one sector or many: SECTOR is x,y · * · *:TYPE · x1:x2,y1:y2 (see {@link SectorSelector}). */
    private Reply many(long gameId, Account a, CountryView v, GameConfig cfg, String sel, java.util.function.Function<Coord, Command> f) { return many(gameId, a, v, cfg, sel, f, null); }

    private Reply many(long gameId, Account a, CountryView v, GameConfig cfg, String sel, java.util.function.Function<Coord, Command> f, String note) {
        List<Command> cmds = SectorSelector.expand(v, cfg, sel).stream().map(f).toList();
        return cmds.size() == 1 ? cmd(gameId, a, cmds.get(0)) : reply(games.commandAll(gameId, a, cmds, "console", note));
    }

    private static Reply reply(GameService.Outcome o) {
        return new Reply(o.accepted() ? (o.info() != null ? o.info() + " (" + o.btuSpent() + " BTU)" : "ok (" + o.btuSpent() + " BTU)") : "", o.accepted(), o.error(), o.view());
    }

    private static void need(String[] t, int n, String usage) { if (t.length < n) throw new IllegalArgumentException("usage: " + usage); }

    /** "x,y" relative to the capital -> absolute, using the view's own relative table. */
    static Coord abs(CountryView v, String s) {
        String[] p = s.split(",");
        if (p.length != 2) throw new IllegalArgumentException("coordinates look like x,y — got '" + s + "'");
        int rx, ry;
        try { rx = Integer.parseInt(p[0].trim()); ry = Integer.parseInt(p[1].trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("coordinates look like x,y — got '" + s + "'"); }
        for (SectorView sv : v.sectors()) if (sv.relative().x() == rx && sv.relative().y() == ry) return sv.at();
        // not in view: extrapolate from the capital (the executor will reject if out of bounds or unowned)
        return new Coord(v.capital().x() + rx, v.capital().y() + ry);
    }

    static String map(CountryView v, GameConfig cfg) {
        if (v.sectors().isEmpty()) return "(nothing visible)";
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        Map<Coord, SectorView> byRel = new HashMap<>();
        for (SectorView s : v.sectors()) { Coord r = s.relative(); byRel.put(r, s); minX = Math.min(minX, r.x()); maxX = Math.max(maxX, r.x()); minY = Math.min(minY, r.y()); maxY = Math.max(maxY, r.y()); }
        StringBuilder sb = new StringBuilder();
        // two header rows: sign/tens and units, one column per x
        sb.append("     ");
        for (int x = minX; x <= maxX; x++) { int a = Math.abs(x); sb.append(x < 0 && a < 10 ? '-' : a >= 10 ? (char) ('0' + (a / 10) % 10) : ' ').append(' '); }
        sb.append("\n     ");
        for (int x = minX; x <= maxX; x++) sb.append((char) ('0' + Math.abs(x) % 10)).append(' ');
        sb.append('\n');
        for (int y = minY; y <= maxY; y++) {
            sb.append(String.format("%4d ", y));
            if (((v.capital().y() + y) & 1) == 1) sb.append(' ');   // odd rows of the absolute grid are shifted
            for (int x = minX; x <= maxX; x++) {
                SectorView s = byRel.get(new Coord(x, y));
                char g;
                if (s == null) g = ' ';
                else if (s.full()) g = cfg.sectorType(s.designation()).glyph().charAt(0);
                else if (s.terrain().equals("ocean")) g = '.';
                else if (s.owner() >= 0) g = s.sanctuary() ? 's' : '?';
                else g = '-';
                sb.append(g).append(' ');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** One line per ship, relative coordinates. */
    static String fleet(CountryView v, GameConfig cfg) {
        if (v.ships().isEmpty()) return "no ships — build one in a harbour: build x,y fishing_boat";
        StringBuilder sb = new StringBuilder(String.format("%-4s %-24s %-8s %5s %5s %-11s %-18s %s%n", "id", "class", "at", "eff", "speed", "load/hold", "going", "last update"));
        for (var s : v.ships()) {
            String going = s.lane() != null ? "lane " + rel(s.lane().fromRelative()) + (s.lane().outbound() ? " → " : " ← ") + rel(s.lane().toRelative()) : s.mission() != null && !s.mission().isBlank() ? ("fish".equals(s.mission()) ? "fishing" : "mining") + " from " + rel(s.homeRelative()) + (s.destRelative() != null ? " → " + rel(s.destRelative()) : "") : s.destRelative() != null ? "to " + rel(s.destRelative()) : s.docked() ? "in harbour" : "holding";
            sb.append(String.format("%-4d %-24s %-8s %5.0f %5d %-11s %-18s %s%n", s.id(), s.cls() + (s.name() == null || s.name().isBlank() ? "" : " " + s.name()), rel(s.relative()), s.efficiency(), s.hexesPerUpdate(), (int) s.load() + "/" + (int) s.hold(), going, s.note()));
        }
        return sb.toString();
    }
    private static String rel(Coord c) { return c.x() + "," + c.y(); }

    /** What radar and the lookouts have: where a ship was last seen, not where it is now (issue #75). */
    static String contacts(CountryView v) {
        if (v.contacts().isEmpty()) return "no contacts — nothing of anyone else's is on the plot";
        StringBuilder sb = new StringBuilder(String.format("%-8s %-10s %-24s %-9s %s%n", "at", "country", "class", "band", "seen"));
        for (var c : v.contacts())
            sb.append(String.format("%-8s %-10s %-24s %-9s %s%n", rel(c.relative()), c.ownerName() == null ? "?" : c.ownerName(),
                    c.cls() == null ? "?" : c.cls(), c.band(), c.age() == 0 ? "this update" : c.age() + (c.age() == 1 ? " update ago" : " updates ago")));
        return sb.toString();
    }

    static String census(CountryView v) {
        StringBuilder sb = new StringBuilder(String.format("%-8s %-3s %-4s %4s %4s %6s %6s %6s %6s %6s %6s%n", "sect", "des", "eff", "mob", "road", "civ", "mil", "food", "iron", "lcm", "hcm"));
        for (SectorView s : v.sectors()) {
            if (!s.full()) continue;
            sb.append(String.format("%-8s %-3s %4.0f %4.0f %4.0f %6.0f %6.0f %6.0f %6.0f %6.0f %6.0f%n", s.relative().x() + "," + s.relative().y(), glyph(s), s.efficiency(), s.mobility(), s.roadLevel(),
                    s.stock().getOrDefault("civ", 0.0), s.stock().getOrDefault("mil", 0.0), s.stock().getOrDefault("food", 0.0), s.stock().getOrDefault("iron", 0.0), s.stock().getOrDefault("lcm", 0.0), s.stock().getOrDefault("hcm", 0.0)));
        }
        return sb.toString();
    }

    private static String glyph(SectorView s) { return s.designation().length() > 3 ? s.designation().substring(0, 3) : s.designation(); }

    /**
     * The command syntax, read from the player guide rather than held here (issue #118). The guide's
     * {@code commands.md} owns the text and is packaged onto the classpath at {@code help/}; this
     * prints the first fenced block from it, which is the quick reference. One source, two
     * presentations — a command added to one cannot go missing from the other.
     */
    static final String HELP = loadHelp();

    private static String loadHelp() {
        try (java.io.InputStream in = Console.class.getClassLoader().getResourceAsStream("help/commands.md")) {
            if (in == null) return "the command reference is missing from this build";
            String md = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int open = md.indexOf("```text");
            if (open < 0) return "the command reference has no quick-reference block";
            int from = md.indexOf('\n', open) + 1;
            int to = md.indexOf("```", from);
            return to < 0 ? md.substring(from) : md.substring(from, to).stripTrailing();
        } catch (java.io.IOException e) {
            return "the command reference could not be read: " + e.getMessage();
        }
    }
}
