package org.hastingtx.empire.engine.config;

/**
 * A named curve. {@code type} selects the formula; unused parameters are null.
 * <ul>
 *   <li>wolfpack: f(level) = (level - min) / (level - min + lag), 0 when level < min.
 *       The original's prod_eff(): nlmin / nllag from product.config.</li>
 *   <li>saturating: f(level) = baseline + level / (level + k)</li>
 *   <li>log: f(level) = 1 + log_base(1 + level * easy) / 10</li>
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
        Double minMultiplier,
        Double min,
        Double lag,
        Double at0,
        Double at100) {

    public double eval(double level) {
        return switch (type) {
            case "wolfpack" -> {
                double m = min == null ? 0 : min, l = lag == null ? 10 : lag;
                double d = level - m;
                yield d < 0 ? 0.0 : d / (d + l);
            }
            case "saturating" -> (baseline == null ? 0.0 : baseline) + level / (level + k);
            case "log" -> 1.0 + Math.log(1.0 + level * (easy == null ? 1.0 : easy)) / Math.log(base) / 10.0;
            case "constant" -> value;
            case "linear" -> {
                double a = at0 == null ? 1.0 : at0, b = at100 == null ? 1.0 : at100;
                yield a + (b - a) * Math.max(0, Math.min(100, level)) / 100.0;
            }
            case "diminishing" -> {
                double x = Math.max(0, Math.min(100, level)) / 100.0;
                double mm = minMultiplier == null ? 0.0 : minMultiplier;
                yield 1.0 - (1.0 - mm) * (1.0 - (1.0 - x) * (1.0 - x));
            }
            default -> throw new IllegalStateException("unknown curve type: " + type);
        };
    }
}
