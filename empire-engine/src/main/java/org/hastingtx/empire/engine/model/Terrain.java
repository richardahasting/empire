package org.hastingtx.empire.engine.model;

import java.util.Locale;

public enum Terrain {
    OCEAN, WILDERNESS, PLAINS, FOREST, SWAMP, MOUNTAIN;

    /**
     * {@code values()} allocates a fresh array on every call, and {@link Sector} resolves its terrain
     * from an ordinal for every sector of every update (issue #77) — half a million allocations an
     * update at the largest world size. Cache it once; nothing may write to this array.
     */
    private static final Terrain[] VALUES = values();

    public static Terrain byOrdinal(int i) { return VALUES[i]; }

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public boolean isLand() { return this != OCEAN; }

    public static Terrain of(String id) { return valueOf(id.toUpperCase(Locale.ROOT)); }
}
