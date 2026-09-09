package org.hastingtx.empire.engine.config;

import java.util.List;
import java.util.Map;

public record WorldCfg(
        String name,
        int width,
        int height,
        boolean wrapX,
        boolean wrapY,
        long seed,
        CoordinatesCfg coordinates,
        TerrainGenCfg terrain,
        Map<String, Range> elevationByTerrain,
        ResourcesCfg resources) {

    public record CoordinatesCfg(String playerFacing) {}

    public record Range(int min, int max) {}

    public record TerrainGenCfg(
            String generator,
            double landFraction,
            int islandSize,
            int continentCount,
            int spike,
            int minDistanceBetweenCapitals,
            Map<String, Double> landMix) {}

    /** Per-terrain triangular distribution [min, mode, max], 0..100. */
    public record ResourcesCfg(
            Map<String, List<Integer>> fertility,
            Map<String, List<Integer>> minerals,
            Map<String, List<Integer>> gold,
            Map<String, List<Integer>> oil,
            Map<String, List<Integer>> uranium,
            /** Fishing grounds (issue #56): the sea is fertile by region, not by hex. */
            SeaFertilityCfg seaFertility) {}

    /** Sea fertility: one triangular draw per {@code region_size}×{@code region_size} block, jittered per hex. */
    public record SeaFertilityCfg(int regionSize, List<Integer> triangular, int jitter) {}
}
