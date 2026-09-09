package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.model.World;

import java.util.List;

/** {@code notes}: per owned sector ("x,y" absolute), what happened there this update in step order (issue #49). */
public record UpdateResult(World next, List<Event> events, List<Flow> flows, String stateHash, java.util.Map<String, List<String>> notes) {
    public UpdateResult(World next, List<Event> events, List<Flow> flows, String stateHash) { this(next, events, flows, stateHash, java.util.Map.of()); }
}
