package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.model.World;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@code notes}: per owned sector ("x,y" absolute), what happened there this update in step order (issue #49).
 *
 * <p>The state hash is <b>lazy</b> (issue #82). Hashing a world means walking every sector, and on a
 * million-sector map that was most of the cost of an update — paid whether or not anyone asked for the
 * value. It is now computed on the first call to {@link #stateHash()} and remembered, so a caller that
 * never asks never pays.
 */
public record UpdateResult(World next, List<Event> events, List<Flow> flows, Supplier<String> hash, java.util.Map<String, List<String>> notes) {

    public UpdateResult(World next, List<Event> events, List<Flow> flows, Supplier<String> hash) { this(next, events, flows, hash, java.util.Map.of()); }

    /** The world's state hash, computed once on demand. */
    public String stateHash() { return hash.get(); }

    /** A hash of {@code w}, computed on the first call and remembered. */
    public static Supplier<String> lazyHash(World w) {
        return new Supplier<>() {
            private String value;
            @Override public synchronized String get() {
                if (value == null) value = org.hastingtx.empire.engine.update.steps.ApplyStep.hash(w);
                return value;
            }
        };
    }
}
