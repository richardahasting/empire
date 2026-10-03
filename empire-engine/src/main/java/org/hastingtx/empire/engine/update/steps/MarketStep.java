package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.MarketCfg;
import org.hastingtx.empire.engine.model.MarketLot;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Step;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 8b, the market (issue #141; KNOWN buy.c check_market). Every lot with a bid whose time is up sells: the buyer
 * pays the price times {@code buy_tax}, the seller is paid the price, and the goods appear in the buyer's harbour or
 * warehouse. The original settled by the wall clock every five minutes; this engine settles at the update.
 *
 * <p>A sale that cannot go through — the buyer cannot pay, or the sector it named is no longer a working harbour or
 * warehouse of theirs with the room — puts the lot back on the market at the price it reached, with nobody bidding.
 * The original said exactly that ("goods remain on the market") and then deleted the lot, goods and all; here they stay.
 */
public final class MarketStep implements Step {
    public String name() { return "market"; }

    public void run(Ctx ctx) {
        MarketCfg mc = ctx.cfg.options() != null && ctx.cfg.options().market() ? ctx.cfg.economy().market() : null;
        if (mc == null || ctx.market.isEmpty()) return;
        long now = ctx.snap.updateNumber() + 1;   // the update this one makes
        List<MarketLot> left = new ArrayList<>();
        for (MarketLot lot : ctx.market) {
            if (!lot.bid() || lot.settles() > now) { left.add(lot); continue; }
            String what = Ledger.q(lot.amount()) + " " + ctx.com.id(lot.commodity());
            double pays = lot.price() * lot.amount() * mc.buyTax(), paid = lot.price() * lot.amount();
            String why = null;
            if (ctx.country(lot.bidder()).cash() + ctx.led().cash[lot.bidder()] < pays) why = "the buyer could not pay " + money(pays);
            int di = ctx.idx(lot.dest());
            Sector to = ctx.sector(di);
            if (why == null && (to.owner() != lot.bidder() || !mc.sectorTypes().contains(to.designation()) || to.efficiency() < mc.minEfficiency()))
                why = lot.dest() + " is no longer a working " + String.join(" or ", mc.sectorTypes()) + " of the buyer's";
            if (why == null && Math.floor(ctx.capacity(to, lot.commodity())) - (to.stock().get(lot.commodity()) + ctx.led().st(di, lot.commodity())) < lot.amount())
                why = lot.dest() + " has no room for " + what;
            if (why != null) {
                left.add(lot.withoutBid());
                // the seller hears only that the buyer's side failed: where the buyer meant the goods to go, and what they
                // could not pay, are the buyer's business
                ctx.led().event("market_failed", lot.owner(), lot.from(), "lot " + lot.id() + " (" + what + ") did not sell: the buyer's side of it fell through; it is back on the market", lot.amount());
                ctx.led().event("market_failed", lot.bidder(), lot.dest(), "your bid on lot " + lot.id() + " (" + what + ") fell through: " + why, lot.amount());
                continue;
            }
            ctx.led().fromMarket(di, lot.commodity(), lot.amount());
            ctx.led().cash[lot.bidder()] -= pays;
            ctx.led().cash[lot.owner()] += paid;
            ctx.led().note(di, "bought lot " + lot.id() + ": " + what + " for " + money(pays));
            ctx.led().event("market_sale", lot.owner(), lot.from(), "lot " + lot.id() + " sold: " + what + " to " + ctx.country(lot.bidder()).name() + " for " + money(paid), paid);
            ctx.led().event("market_sale", lot.bidder(), lot.dest(), "you bought lot " + lot.id() + ": " + what + " from " + ctx.country(lot.owner()).name() + " for " + money(pays) + ", now in " + lot.dest(), pays);
        }
        ctx.market.clear();
        ctx.market.addAll(left);
    }

    private static String money(double v) { return String.format(java.util.Locale.ROOT, "$%.2f", v); }
}
