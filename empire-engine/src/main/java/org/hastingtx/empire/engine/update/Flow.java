package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.model.Coord;

import java.util.List;

/**
 * One resolved movement, for the UI's flow animation and the sim's report.
 * {@code path} starts at the origin; {@code hopsDelivered} is how many hops the cargo
 * actually advanced this update; {@code completed} is whether it reached path's end.
 */
public record Flow(String kind, int owner, int commodity, double qtyPlanned, double qtyMoved,
                   List<Coord> path, int hopsDelivered, boolean completed, String holdReason) {}
