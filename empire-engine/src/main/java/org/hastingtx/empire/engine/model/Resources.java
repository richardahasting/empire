package org.hastingtx.empire.engine.model;

/** Per-sector endowments, 0..100. */
public record Resources(int fertility, int minerals, int gold, int oil, int uranium) {
    public static final Resources NONE = new Resources(0, 0, 0, 0, 0);

    public int get(String gate) {
        return switch (gate) {
            case "fertility" -> fertility;
            case "minerals" -> minerals;
            case "gold" -> gold;
            case "oil" -> oil;
            case "uranium" -> uranium;
            default -> throw new IllegalArgumentException("unknown resource gate: " + gate);
        };
    }
}
