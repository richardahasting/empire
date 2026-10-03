package org.hastingtx.empire.engine.model;

/**
 * A lot on the commodity market (issue #141; KNOWN Wolfpack Empire include/commodity.h {@code struct comstr}): goods a
 * country put up for sale, taken out of its harbour or warehouse the moment it listed them, and held here until somebody
 * buys them or the seller takes them back.
 *
 * <p>{@code price} is per unit: the asking price until somebody bids, then the high bid. {@code bidder} is the high
 * bidder ({@link #NOBODY} before any bid) and {@code dest} the sector of theirs the goods go to. The lot sells at the
 * update {@code settles}, if it has a bid; a lot nobody bids on stays up until its seller withdraws it.
 */
public record MarketLot(long id, int owner, int commodity, double amount, double price, int bidder, Coord from, Coord dest,
                        long listed, long settles) {
    public static final int NOBODY = -1;

    public boolean bid() { return bidder != NOBODY; }
    public MarketLot withBid(int who, double perUnit, Coord to, long settlesAt) { return new MarketLot(id, owner, commodity, amount, perUnit, who, from, to, listed, settlesAt); }
    public MarketLot withPrice(double perUnit, long now, long settlesAt) { return new MarketLot(id, owner, commodity, amount, perUnit, bidder, from, dest, now, settlesAt); }
    /** The bid fell through: back on the market at the price it had reached, with nobody bidding. */
    public MarketLot withoutBid() { return new MarketLot(id, owner, commodity, amount, price, NOBODY, from, null, listed, settles); }
}
