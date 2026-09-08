package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.model.Coord;

/** Something the update wants to tell someone. {@code country} -1 = world-visible news. */
public record Event(String type, int country, Coord at, String message, double amount) {}
