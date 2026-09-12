package org.hastingtx.empire.engine.config;

import java.util.List;

/**
 * What the world is told (issue #121). {@code nameCountries} is the disclosure choice: a milestone
 * that names its country tells everyone that country has the tech and materials for the thing, which
 * is a real leak — and here a deliberate one, because bragging rights are the feature.
 */
public record NewsCfg(boolean enabled, boolean nameCountries, List<String> milestoneExclude) {
    public static final NewsCfg DEFAULT = new NewsCfg(true, true, List.of("wilderness", "sanctuary", "wasteland", "capital"));
}
