package org.hastingtx.empire.engine.config;

/**
 * A named curve. {@code type} selects the formula; unused parameters are null.
 * <ul>
 *   <li>saturating: f(level) = baseline + level / (level + k)</li>
 *   <li>log: f(level) = 1 + log_base(1 + level * easy) / 10   (Wolfpack-style diminishing)</li>
 *   <li>constant: f = value</li>
 *   <li>linear: f(level) = at0 + (at100 - at0) * level/100</li>
 *   <li>diminishing: multiplier = 1 - (1 - minMultiplier) * (1 - (1 - level/100)^2)</li>
 * </ul>
 */
public record CurveCfg(
        String type,
        Double k,
        Double baseline,
        Double base,
        Double easy,
        Double value,
        Double minMultiplier) {

    public double eval(double level) {
        return switch (type) {
            case "saturating" -> (baseline == null ? 0.0 : baseline) + level / (level + k);
            case "log" -> 1.0 + Math.log(1.0 + level * (easy == null ? 1.0 : easy)) / Math.log(base) / 10.0;
            case "constant" -> value;
            case "diminishing" -> {
                double x = Math.max(0, Math.min(100, level)) / 100.0;
                double m = minMultiplier == null ? 0.0 : minMultiplier;
                yield 1.0 - (1.0 - m) * (1.0 - (1.0 - x) * (1.0 - x));
            }
            default -> throw new IllegalStateException("unknown curve type: " + type);
        };
    }
}
