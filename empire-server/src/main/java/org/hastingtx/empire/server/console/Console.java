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
    public Console(GameService games) { this.games = games; }

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
                case "des", "designate" -> { need(t, 3, "des x,y type"); yield cmd(gameId, a, new Command.Designate(abs(v, t[1]), t[2])); }
                case "thresh", "threshold" -> { need(t, 4, "thresh x,y commodity amount"); yield cmd(gameId, a, new Command.Threshold(abs(v, t[1]), t[2], Double.parseDouble(t[3]))); }
                case "dist", "distribute" -> { need(t, 3, "dist x,y cx,cy|none"); yield cmd(gameId, a, new Command.Distribute(abs(v, t[1]), t[2].equalsIgnoreCase("none") ? null : abs(v, t[2]))); }
                case "move" -> { need(t, 5, "move commodity from_x,y to_x,y qty"); yield cmd(gameId, a, new Command.Move(abs(v, t[2]), abs(v, t[3]), t[1], Double.parseDouble(t[4]))); }
                case "road" -> { need(t, 3, "road x,y LEVEL"); yield cmd(gameId, a, new Command.BuildRoad(abs(v, t[1]), Double.parseDouble(t[2]))); }
                case "expl", "explore" -> { need(t, 4, "expl from_x,y to_x,y civs"); yield cmd(gameId, a, new Command.Explore(abs(v, t[1]), abs(v, t[2]), Double.parseDouble(t[3]))); }
                default -> new Reply("", false, "unknown command '" + verb + "' (try help)", null);
            };
        } catch (IllegalArgumentException e) {
            return new Reply("", false, e.getMessage(), null);
        }
    }

    private Reply cmd(long gameId, Account a, Command c) {
        GameService.Outcome o = games.command(gameId, a, c, "console");
        return new Reply(o.accepted() ? "ok (" + o.btuSpent() + " BTU)" : "", o.accepted(), o.error(), o.view());
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
                else if (s.owner() >= 0) g = '?';
                else g = '-';
                sb.append(g).append(' ');
            }
            sb.append('\n');
        }
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

    static final String HELP = """
            map                          your map (relative coordinates, capital at 0,0)
            census                       one line per owned sector
            break                        break sanctuary
            des x,y TYPE                 designate a sector (agribusiness, mine, light_manufacturing, warehouse, ...)
            thresh x,y COMMODITY N       set a distribution threshold (negative clears)
            dist x,y cx,cy | none        name a sector's distribution centre
            move COMMODITY x,y x2,y2 N   queue a move for the next update
            expl x,y x2,y2 N             explore into an adjacent unowned sector with N civilians
            road x,y LEVEL               standing order: pave this sector toward LEVEL (0 cancels)
            """;
}
