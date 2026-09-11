package org.hastingtx.empire.server.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the deity may change about a world at creation time (issues #81, #105). Every field is
 * nullable and a null keeps whatever the preset says. {@code water} is the percent of the map that
 * is sea; the rest map onto {@code world.terrain.*} and {@code world.wrap_*}.
 *
 * <p>{@link #patch} works on the raw YAML map rather than the bound config so that what the game
 * stores is exactly what it plays by — the snapshot and the rules can never drift apart.
 */
public record WorldOverrides(Integer width, Integer height, Double water,
                             Integer islandSize, Integer spike, Integer minCapitalDistance,
                             Boolean wrapX, Boolean wrapY, Map<String, Double> landMix) {

    public static final WorldOverrides NONE = new WorldOverrides(null, null, null, null, null, null, null, null, null);

    /** Biggest world we will generate: 1024x2048, measured at ~8 GB and ~82 s an update (issue #81). */
    public static final int MAX_SECTORS = 2_097_152;
    public static final int MAX_DIMENSION = 2048;
    public static final int MIN_DIMENSION = 16;
    /** Smallest island the generator will grow; below this it clamps rather than obeying. */
    public static final int MIN_ISLAND_SIZE = 3;

    /** The land terrains the generator can draw, in the order the form shows them. */
    public static final List<String> LAND_TERRAINS = List.of("wilderness", "plains", "forest", "mountain", "swamp");

    public boolean empty() {
        return width == null && height == null && water == null && islandSize == null && spike == null
                && minCapitalDistance == null && wrapX == null && wrapY == null && (landMix == null || landMix.isEmpty());
    }

    /**
     * Return {@code raw} with this override applied to its {@code world} block. The input is not
     * modified. Everything is validated here, as {@link IllegalArgumentException}, so the deity gets
     * a 400 with a sentence rather than a 500 from somewhere inside the generator.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> patch(Map<String, Object> raw, int countries) {
        if (empty()) return raw;
        Map<String, Object> out = new LinkedHashMap<>(raw);
        Map<String, Object> world = new LinkedHashMap<>((Map<String, Object>) out.getOrDefault("world", Map.of()));
        Map<String, Object> terrain = new LinkedHashMap<>((Map<String, Object>) world.getOrDefault("terrain", Map.of()));

        int w = width != null ? width : ((Number) world.get("width")).intValue();
        int h = height != null ? height : ((Number) world.get("height")).intValue();
        if (w < MIN_DIMENSION || h < MIN_DIMENSION) throw new IllegalArgumentException("a world is at least " + MIN_DIMENSION + " sectors on a side");
        if (w > MAX_DIMENSION || h > MAX_DIMENSION) throw new IllegalArgumentException("a world is at most " + MAX_DIMENSION + " sectors on a side");
        if ((long) w * h > MAX_SECTORS)
            throw new IllegalArgumentException(w + "x" + h + " is " + (long) w * h + " sectors; the most we will generate is " + MAX_SECTORS + " (e.g. 1024x2048)");
        world.put("width", w);
        world.put("height", h);

        if (wrapX != null) world.put("wrap_x", wrapX);
        if (wrapY != null) world.put("wrap_y", wrapY);
        // the engine refuses an odd height on a north-south wrap; say so before we have spent a generation finding out
        if ((Boolean) world.getOrDefault("wrap_y", Boolean.TRUE) && (h & 1) == 1)
            throw new IllegalArgumentException("height must be even when the world wraps north-south (it is " + h + ")");

        if (water != null) {
            if (water < 0 || water > 95) throw new IllegalArgumentException("water is 0 to 95 percent — the generator needs somewhere to put the capitals");
            terrain.put("land_fraction", Math.round((1.0 - water / 100.0) * 1000.0) / 1000.0);
        }
        if (islandSize != null) {
            if (islandSize < MIN_ISLAND_SIZE) throw new IllegalArgumentException("an island is at least " + MIN_ISLAND_SIZE + " sectors");
            if (islandSize > (long) w * h) throw new IllegalArgumentException("an island cannot be bigger than the world");
            terrain.put("island_size", islandSize);
        }
        if (spike != null) {
            if (spike < 0 || spike > 100) throw new IllegalArgumentException("spike is 0 to 100");
            terrain.put("spike", spike);
        }
        if (minCapitalDistance != null) {
            if (minCapitalDistance < 1) throw new IllegalArgumentException("capitals are at least 1 sector apart");
            terrain.put("min_distance_between_capitals", minCapitalDistance);
        }
        // check the spacing that will actually be used, whether it came from this override or the preset:
        // shrinking the map or adding countries can make the preset's own spacing impossible
        Object spacing = terrain.get("min_distance_between_capitals");
        if (spacing instanceof Number d) checkCapitalsFit(w, h, countries, d.intValue());
        if (landMix != null && !landMix.isEmpty()) terrain.put("land_mix", normalisedLandMix(landMix));

        world.put("terrain", terrain);
        out.put("world", world);
        return out;
    }

    /**
     * The land mix arrives as weights but is stored as fractions summing to 1.0, which is what the
     * schema promises and what {@code WorldGenerator.pickLandType} assumes. Normalising means the
     * form can show five numbers that do not quite add up and still produce a legal world.
     */
    static Map<String, Double> normalisedLandMix(Map<String, Double> mix) {
        double total = 0;
        for (Map.Entry<String, Double> e : mix.entrySet()) {
            if (!LAND_TERRAINS.contains(e.getKey())) throw new IllegalArgumentException("not a land terrain: " + e.getKey());
            if (e.getValue() == null || e.getValue() < 0) throw new IllegalArgumentException(e.getKey() + " cannot be negative");
            total += e.getValue();
        }
        if (total <= 0) throw new IllegalArgumentException("the land mix is all zero — some terrain has to cover the land");
        Map<String, Double> out = new LinkedHashMap<>();
        List<String> kept = new ArrayList<>(LAND_TERRAINS);
        for (String t : kept) out.put(t, Math.round(mix.getOrDefault(t, 0.0) / total * 1000.0) / 1000.0);
        return out;
    }

    /**
     * How many hexes lie within {@code r} steps of a hex, centre included: the hex "disc" number,
     * 1, 7, 19, 37 … Used to bound how many capitals can be packed into a world.
     */
    static long disc(int r) { return 3L * r * r + 3L * r + 1L; }

    /**
     * The largest radius whose discs around two capitals cannot overlap when the capitals are at
     * least {@code minDistance} apart. Two hexes that far apart have disjoint discs of this radius,
     * because twice it is still less than the distance between them.
     */
    static int packingRadius(int minDistance) { return Math.max(0, (minDistance - 1) / 2); }

    /** Most capitals that could possibly be packed into {@code w}x{@code h} at this spacing. */
    static long capitalsThatFit(int w, int h, int minDistance) {
        return (long) w * h / disc(packingRadius(minDistance));
    }

    /** Widest spacing at which {@code countries} capitals could possibly fit. At least 1. */
    static int widestSpacingFor(int w, int h, int countries) {
        int best = 1;
        for (int d = 1; d <= w + h; d++) {
            if (countries * disc(packingRadius(d)) > (long) w * h) break;
            best = d;
        }
        return best;
    }

    /**
     * Reject a min-capital-distance the generator cannot satisfy, before it spends 20,000 rejection
     * samples discovering that for itself and throws an IllegalStateException at the deity as a 500
     * ({@code WorldGenerator.placeCapitals}).
     *
     * <p>The test is the conservative packing bound: capitals at least {@code minDistance} apart have
     * disjoint discs of radius {@code (minDistance-1)/2}, so those discs must all fit inside the map.
     * When they cannot, no legal arrangement exists and refusing is certainly right — this never turns
     * away a world that would have generated. It does let some impossible-in-practice worlds through,
     * because rejection sampling gives up long before the bound bites; those are caught by
     * {@code GameService}, which turns the generator's own surrender into a 400 rather than a 500.
     */
    static void checkCapitalsFit(int width, int height, int countries, int minDistance) {
        if (countries <= 1) return;
        long need = countries * disc(packingRadius(minDistance));
        long have = (long) width * height;
        if (need <= have) return;
        long maxCountries = capitalsThatFit(width, height, minDistance);
        int maxSpacing = widestSpacingFor(width, height, countries);
        throw new IllegalArgumentException(
                countries + " capitals cannot be " + minDistance + " sectors apart on " + width + "x" + height
                        + " — that needs room for " + need + " sectors and there are " + have
                        + ". At this spacing " + maxCountries + " would fit; for " + countries
                        + " countries the widest spacing is " + maxSpacing + ".");
    }
}
