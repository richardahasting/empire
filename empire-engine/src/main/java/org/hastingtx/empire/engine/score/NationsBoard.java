package org.hastingtx.empire.engine.score;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * How everyone is doing, as one country is allowed to see it (issue #120).
 *
 * <p>The composite behind this is computed from the whole world — every sector's owner, population
 * and efficiency, plus each country's cash and tech. Publishing that raw would hand every player an
 * exact census of every rival, and territory and population are exactly what detection and map memory
 * exist to make expensive to learn. A board built carelessly is a bigger intelligence leak than any
 * radar station, and it would quietly undo a large part of the game.
 *
 * <p>So the board is built <em>for a viewer</em>. Their own country is always exact — it is their own
 * census, and they can count it themselves. Everyone else is disclosed as far as
 * {@code scoring.visibility} allows and no further: the numbers are dropped here, in the engine,
 * rather than trusted not to be rendered.
 */
public final class NationsBoard {
    private NationsBoard() {}

    /**
     * One country's standing. {@code score}, {@code sectors} and {@code civilians} are null for
     * anyone but the viewer unless the game publishes exact figures — null rather than zero, so a
     * client cannot mistake "not telling you" for "nothing".
     */
    public record Standing(int countryId, String name, int rank, boolean you, String band,
                           Double score, Integer sectors, Double civilians, Double tech, boolean bankrupt) {}

    /**
     * A viewer who is nobody in particular: in no country, and owed nothing about anyone. Distinct
     * from {@link #DEITY} on purpose — they are both "not a player", and treating them alike is how
     * a spectator ends up reading everybody's census.
     */
    public static final int OUTSIDER = -2;

    /** The deity, who is not playing and sees the board whole. */
    public static final int DEITY = -1;

    /** The board as {@code viewer} may see it, best first. */
    public static List<Standing> of(GameConfig cfg, World w, int viewer) {
        Commodities com = Commodities.of(cfg);
        int n = w.countries().size();
        int[] sectors = new int[n];
        double[] civ = new double[n], eff = new double[n];
        for (Sector s : w.sectors()) {
            int o = s.owner();
            if (o < 0 || o >= n) continue;
            sectors[o]++;
            civ[o] += s.stock().get(com.civ);
            eff[o] += s.efficiency();
        }

        record Row(Country c, double score, int sectors, double civ) {}
        List<Row> rows = new ArrayList<>();
        for (Country c : w.countries())
            rows.add(new Row(c, Scoring.score(cfg, c, sectors[c.id()], civ[c.id()], eff[c.id()]), sectors[c.id()], civ[c.id()]));
        rows.sort(Comparator.comparingDouble(Row::score).reversed());

        String visibility = cfg.scoring().visibilityOrDefault();
        List<String> bands = cfg.scoring().bandsOrDefault();
        boolean exact = visibility.equals("exact");

        List<Standing> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean you = r.c().id() == viewer;
            boolean open = you || exact || viewer == DEITY;
            String band = visibility.equals("rank") && !open ? null : bandFor(i, rows.size(), bands);
            out.add(new Standing(r.c().id(), r.c().name(), i + 1, you, band,
                    open ? r.score() : null,
                    open ? r.sectors() : null,
                    open ? r.civ() : null,
                    open ? r.c().levels().tech() : null,
                    r.c().bankrupt()));
        }
        return out;
    }

    /** Which band a position falls in: the list is ordered worst to best, so the top rank gets the last. */
    private static String bandFor(int index, int total, List<String> bands) {
        if (total <= 1) return bands.get(bands.size() - 1);
        int slot = (int) Math.floor((double) (total - 1 - index) * bands.size() / total);
        return bands.get(Math.max(0, Math.min(bands.size() - 1, slot)));
    }
}
