package org.hastingtx.empire.engine.config;

import java.util.List;

/**
 * The commodity market (issue #141): KNOWN Wolfpack Empire commands/{sell,buy,rese,mark}.c, switched on by
 * {@code options.market} as the original's MARKET option. The original timed lots by the wall clock; this engine has
 * no clock, so a lot's time is counted in updates.
 */
public record MarketCfg(
        /** KNOWN sell.c:84, buy.c:146: the sector types goods are sold from and bought into. */
        List<String> sectorTypes,
        /** KNOWN sell.c:89, buy.c:151: and only while they are at least this efficient. */
        double minEfficiency,
        /** KNOWN sell.c:113: no unit dearer than this. */
        double maxPrice,
        /** KNOWN buy.c:166: a bid beats the price by at least this much a unit. */
        double minRaise,
        /** KNOWN constants.c buytax: the buyer pays the price times this. */
        double buyTax,
        /** GUESS for MARK_DELAY (7200 s of wall clock): updates from listing to sale. */
        int delayUpdates,
        /** GUESS for the original's last-5-minutes rule: a new high bid this close to the sale pushes it back to this many. */
        int snipeUpdates,
        /** KNOWN item.config i_sell: what cannot be sold. */
        List<String> unsellable,
        /** KNOWN constants.c tradetax: the share of a ship, plane or unit's price its seller keeps. Null: no object trade. */
        Double tradeTax,
        /** GUESS for TRADE_DELAY (7200 s of wall clock): updates from set to sale. */
        Integer tradeDelayUpdates) {
    public boolean objectTrade() { return tradeTax != null && tradeDelayUpdates != null; }
}
