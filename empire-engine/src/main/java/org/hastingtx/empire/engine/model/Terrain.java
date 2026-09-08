package org.hastingtx.empire.engine.model;

import java.util.Locale;

public enum Terrain {
    OCEAN, WILDERNESS, PLAINS, FOREST, SWAMP, MOUNTAIN;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public boolean isLand() { return this != OCEAN; }

    public static Terrain of(String id) { return valueOf(id.toUpperCase(Locale.ROOT)); }
}
