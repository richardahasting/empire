package org.hastingtx.empire.server.console;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.engine.view.CountryView.SectorView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a command names one sector, it may name many (issue #38). The same syntax serves the
 * console and the panels' {@code scope}:
 * <ul>
 *   <li>{@code x,y} — one sector, relative to the capital</li>
 *   <li>{@code *} — every sector you own</li>
 *   <li>{@code *:TYPE} — every sector you own with that designation (id or glyph)</li>
 *   <li>{@code x1:x2,y1:y2} — every sector you own inside that rectangle (relative coordinates, inclusive)</li>
 * </ul>
 * Sectors come back in row order so a summary reads the way the map does.
 */
public final class SectorSelector {
    private SectorSelector() {}

    public static boolean isMass(String s) { return s.startsWith("*") || s.contains(":"); }

    public static List<Coord> expand(CountryView v, GameConfig cfg, String sel) {
        String s = sel == null ? "" : sel.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("which sector? x,y · * · *:TYPE · x1:x2,y1:y2");
        if (!isMass(s)) return List.of(Console.abs(v, s));
        List<SectorView> mine = v.sectors().stream().filter(SectorView::full)
                .sorted(Comparator.comparingInt((SectorView x) -> x.relative().y()).thenComparingInt(x -> x.relative().x())).toList();
        List<Coord> out = new ArrayList<>();
        if (s.equals("*")) {
            for (SectorView x : mine) out.add(x.at());
            if (out.isEmpty()) throw new IllegalArgumentException("you own no sectors");
        } else if (s.startsWith("*:")) {
            String id = resolveType(cfg, s.substring(2).trim());
            for (SectorView x : mine) if (id.equals(x.designation())) out.add(x.at());
            if (out.isEmpty()) throw new IllegalArgumentException("none of your sectors is " + id);
        } else {
            int[] r = rect(s);
            for (SectorView x : mine) { Coord c = x.relative(); if (c.x() >= r[0] && c.x() <= r[1] && c.y() >= r[2] && c.y() <= r[3]) out.add(x.at()); }
            if (out.isEmpty()) throw new IllegalArgumentException("none of your sectors is inside " + s);
        }
        return out;
    }

    /** A designation by id ("agribusiness") or by map glyph ("a"). */
    static String resolveType(GameConfig cfg, String t) {
        if (t.isEmpty()) throw new IllegalArgumentException("*:TYPE needs a designation, e.g. *:agribusiness or *:a");
        if (cfg.hasSectorType(t)) return t;
        for (SectorTypeCfg st : cfg.economy().sectorTypes()) if (st.glyph().equals(t)) return st.id();
        throw new IllegalArgumentException("unknown designation '" + t + "'");
    }

    /** {x1, x2, y1, y2}, each side normalised so x1 <= x2. A side without ':' is a single value. */
    static int[] rect(String s) {
        String[] p = s.split(",");
        if (p.length != 2) throw new IllegalArgumentException("a rectangle looks like x1:x2,y1:y2 — got '" + s + "'");
        int[] xs = span(p[0], s), ys = span(p[1], s);
        return new int[] {xs[0], xs[1], ys[0], ys[1]};
    }

    private static int[] span(String part, String whole) {
        String[] q = part.split(":", -1);
        try {
            if (q.length > 2) throw new NumberFormatException();
            int a = Integer.parseInt(q[0].trim()), b = q.length > 1 ? Integer.parseInt(q[1].trim()) : a;
            return new int[] {Math.min(a, b), Math.max(a, b)};
        } catch (NumberFormatException e) { throw new IllegalArgumentException("a rectangle looks like x1:x2,y1:y2 — got '" + whole + "'"); }
    }
}
