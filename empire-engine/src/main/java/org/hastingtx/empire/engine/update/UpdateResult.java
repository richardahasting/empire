package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.model.World;

import java.util.List;

public record UpdateResult(World next, List<Event> events, List<Flow> flows, String stateHash) {}
