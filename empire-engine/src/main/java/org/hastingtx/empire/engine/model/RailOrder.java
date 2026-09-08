package org.hastingtx.empire.engine.model;

/** A rail shipment between two depots, validated for connectivity at issue and executed at the update. */
public record RailOrder(int owner, Coord from, Coord to, int commodity, double qty, long issuedUpdate) {}
