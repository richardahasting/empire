package org.hastingtx.empire.engine.model;

/** A queued manual move, executed by the flow planner at the next update (range-and-hold). */
public record MoveOrder(int owner, Coord from, Coord to, int commodity, double qty, long issuedUpdate) {}
