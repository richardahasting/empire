package org.hastingtx.empire.engine.model;

public record Levels(double tech, double research, double education, double happiness) {
    public static final Levels ZERO = new Levels(0, 0, 0, 0);
    public double get(String id) {
        return switch (id) {
            case "tech" -> tech; case "research" -> research; case "education" -> education; case "happiness" -> happiness;
            default -> throw new IllegalArgumentException("unknown level: " + id);
        };
    }
    public Levels plus(double dt, double dr, double de, double dh) {
        return new Levels(tech + dt, research + dr, education + de, happiness + dh);
    }
}
