package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.MarketCfg;
import org.hastingtx.empire.engine.model.MarketLot;
import org.hastingtx.empire.engine.model.Ship;
import org.hastingtx.empire.engine.model.TradeLot;
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
        if (mc == null) return;
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
            if (why == null && room(ctx, di, to, lot.commodity()) < lot.amount()) why = lot.dest() + " has no room for " + what;
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
        if (mc.objectTrade()) trades(ctx, mc, now);
    }

    /**
     * Ships, planes and units (KNOWN trad.c check_trade). A lot whose thing is gone, or no longer its seller's, is
     * dropped. One with a bid whose time is up sells: the buyer pays the price and the seller keeps {@code trade_tax} of
     * it; a ship changes hands where she lies, with her hold and whoever is aboard her and none of her old orders; a
     * plane flies to the buyer's airfield and a unit goes to their headquarters. If the buyer cannot pay, or the place
     * they named is no longer theirs, the lot is taken off the market and the thing stays with its seller.
     */
    private static void trades(Ctx ctx, MarketCfg mc, long now) {
        List<TradeLot> left = new ArrayList<>();
        for (TradeLot lot : ctx.trades) {
            int at = index(ctx, lot);
            if (at < 0) continue;   // gone, or taken from its seller: the lot goes with it
            if (!lot.bid() || lot.settles() > now) { left.add(lot); continue; }
            String what = lot.kind() + " #" + lot.item();
            String why = null;
            if (ctx.country(lot.bidder()).cash() + ctx.led().cash[lot.bidder()] < lot.price()) why = "could not pay " + money(lot.price());
            if (why == null && lot.dest() != null) {
                Sector to = ctx.sector(ctx.idx(lot.dest()));
                String flag = lot.kind().equals(TradeLot.PLANE) ? "builds_planes" : "builds_units";
                if (to.owner() != lot.bidder() || !ctx.type(to).hasFlag(flag) || to.efficiency() < mc.minEfficiency()) why = "had nowhere to take it";
            }
            if (why != null) {
                ctx.led().event("trade_failed", lot.owner(), null, "the buyer of your " + what + " (lot T" + lot.id() + ") could not complete the sale; it is off the market and still yours", lot.price());
                ctx.led().event("trade_failed", lot.bidder(), lot.dest(), "you " + why + " for " + what + " (lot T" + lot.id() + "); it stays with " + ctx.country(lot.owner()).name(), lot.price());
                continue;
            }
            ctx.led().cash[lot.bidder()] -= lot.price();
            ctx.led().cash[lot.owner()] += lot.price() * mc.tradeTax();
            String buyer = ctx.country(lot.bidder()).name(), seller = ctx.country(lot.owner()).name();
            switch (lot.kind()) {
                case TradeLot.SHIP -> {
                    Ship s = ctx.ships.get(at);
                    ctx.ships.set(at, s.soldTo(lot.bidder()).withNote("bought from " + seller));
                    for (int k = 0; k < ctx.units.size(); k++)   // the seller's units aboard her are sold with her; anyone else's stay theirs
                        if (ctx.units.get(k).ship() == s.id() && ctx.units.get(k).owner() == lot.owner()) ctx.units.set(k, ctx.units.get(k).withOwner(lot.bidder()));
                }
                case TradeLot.PLANE -> ctx.planes.set(at, ctx.planes.get(at).withOwner(lot.bidder()).withAt(lot.dest()).withNote("bought from " + seller));
                default -> ctx.units.set(at, ctx.units.get(at).withOwner(lot.bidder()).withAt(lot.dest()).withMobility(0).withNote("bought from " + seller));
            }
            ctx.led().event("trade_sale", lot.owner(), null, "lot T" + lot.id() + " sold: your " + what + " to " + buyer + " for " + money(lot.price()) + " (you keep " + money(lot.price() * mc.tradeTax()) + ")", lot.price());
            ctx.led().event("trade_sale", lot.bidder(), lot.dest(), "you bought " + what + " from " + seller + " for " + money(lot.price()) + (lot.dest() == null ? "" : "; it is at " + lot.dest()), lot.price());
        }
        ctx.trades.clear();
        ctx.trades.addAll(left);
    }

    /** Where the thing a lot sells is in its list, or −1 if it is gone or no longer its seller's. */
    private static int index(Ctx ctx, TradeLot lot) {
        switch (lot.kind()) {
            case TradeLot.SHIP -> { for (int i = 0; i < ctx.ships.size(); i++) if (ctx.ships.get(i).id() == lot.item()) return ctx.ships.get(i).owner() == lot.owner() ? i : -1; }
            case TradeLot.PLANE -> { for (int i = 0; i < ctx.planes.size(); i++) if (ctx.planes.get(i).id() == lot.item()) return ctx.planes.get(i).owner() == lot.owner() ? i : -1; }
            default -> { for (int i = 0; i < ctx.units.size(); i++) if (ctx.units.get(i).id() == lot.item()) return ctx.units.get(i).owner() == lot.owner() ? i : -1; }
        }
        return -1;
    }

    /**
     * Room for more of a commodity, as buy measured it and as apply truncates: goods by capacity, civilians and workers
     * by the population cap they share, military by nothing.
     */
    private static double room(Ctx ctx, int i, Sector to, int c) {
        if (!ctx.com.isPerson(c)) return Math.floor(ctx.capacity(to, c)) - (to.stock().get(c) + ctx.led().st(i, c));
        if (c == ctx.com.civ || c == ctx.com.uw)
            return Math.floor(ctx.maxPopulation(to)) - (to.stock().get(ctx.com.civ) + ctx.led().st(i, ctx.com.civ) + to.stock().get(ctx.com.uw) + ctx.led().st(i, ctx.com.uw));
        return Double.POSITIVE_INFINITY;
    }

    private static String money(double v) { return String.format(java.util.Locale.ROOT, "$%.2f", v); }
}
