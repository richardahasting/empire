package org.hastingtx.empire.engine.model;

import java.util.List;

/**
 * A standing rail run between two depots (issue #70). Ship lanes shuttle a hull back and forth; a rail
 * lane is simpler, because the track is already there — every update it sends what {@code to} needs and
 * {@code from} can spare, and nothing has to sail home empty.
 *
 * <p>An empty {@code cargo} list is not "nothing". It means <em>keep the far end's thresholds topped
 * up</em>: the destination's own {@code thresh} settings say what it wants, and the lane fills them
 * from the network without anybody ordering a train. That is the point of a depot — it stops being a
 * permit to run a train and starts being a distribution link. A list of commodities is the other mode:
 * push these, whatever the far end has asked for.
 */
public record RailLane(int owner, Coord from, Coord to, List<Integer> cargo) {
    public RailLane { cargo = List.copyOf(cargo); }

    /** True when this lane's job is to feed the destination's thresholds rather than push a named list. */
    public boolean feedsThresholds() { return cargo.isEmpty(); }

    public boolean sameRoute(int owner, Coord from, Coord to) {
        return this.owner == owner && this.from.equals(from) && this.to.equals(to);
    }
}
