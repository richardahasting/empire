package org.hastingtx.empire.engine.model;

/**
 * A ship, plane or land unit up for sale (issue #141; KNOWN Wolfpack Empire include/trade.h {@code struct trdstr},
 * commands/set.c and trad.c). {@code kind} is {@link #SHIP}, {@link #PLANE} or {@link #UNIT}, and {@code item} its id.
 * {@code price} is the asking price until somebody bids, then the high bid; {@code bidder} the high bidder
 * ({@link #NOBODY} before any), and {@code dest} where a plane or unit goes if it is sold to them (a ship changes hands
 * where she lies). While it is on the block it stays where it is and does nothing.
 */
public record TradeLot(long id, int owner, String kind, long item, double price, int bidder, Coord dest, long listed, long settles) {
    public static final int NOBODY = -1;
    public static final String SHIP = "ship", PLANE = "plane", UNIT = "unit";

    public boolean bid() { return bidder != NOBODY; }
    public TradeLot withBid(int who, double p, Coord to, long settlesAt) { return new TradeLot(id, owner, kind, item, p, who, to, listed, settlesAt); }
}
