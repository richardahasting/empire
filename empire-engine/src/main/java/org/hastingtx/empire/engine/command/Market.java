package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.MarketCfg;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ledger;

/**
 * The commodity market (issue #141), as Wolfpack Empire ran it under its MARKET option: {@code sell} takes goods out
 * of a harbour or warehouse and lists them, {@code buy} bids on a lot, {@code reset} cuts an unbid lot's price or
 * withdraws it, and the update sells every lot whose time is up to its high bidder ({@code MarketStep}).
 *
 * <p>The original counted a lot's time by the wall clock; this engine has no clock, so it counts updates.
 */
final class Market {
    private Market() {}

    private static String q(double v) { return Ledger.q(v); }
    private static String money(double v) { return String.format(java.util.Locale.ROOT, "$%.2f", v); }

    /** The market's rules, or null when this game has none. */
    static MarketCfg cfg(GameConfig cfg) {
        return cfg.options() != null && cfg.options().market() ? cfg.economy().market() : null;
    }

    /** A working harbour or warehouse of {@code owner}'s: where goods are sold from and bought into (KNOWN sell.c:84-89). */
    static String notAMarketSector(GameConfig cfg, MarketCfg mc, Sector s, int owner, Coord at) {
        if (s == null || s.owner() != owner) return "you do not own " + at;
        if (!mc.sectorTypes().contains(s.designation())) return at + " is a " + s.designation().replace('_', ' ') + "; goods are bought and sold through a " + String.join(" or ", mc.sectorTypes());
        if (s.efficiency() < mc.minEfficiency()) return at + " is at " + q(s.efficiency()) + "%; it trades at " + q(mc.minEfficiency()) + "% or better";
        return null;
    }

    /** KNOWN sell.c: out of the sector now, onto the market at a price a unit. */
    static CommandResult sell(GameConfig cfg, Commodities com, World w, Country c, Command.Sell s) {
        MarketCfg mc = cfg(cfg);
        if (mc == null) return CommandResult.fail(w, "this game has no market");
        if (s.sector() == null || !w.inBounds(s.sector())) return CommandResult.fail(w, "sell from where?");
        Sector sec = w.sector(s.sector());
        String no = notAMarketSector(cfg, mc, sec, c.id(), s.sector());
        if (no != null) return CommandResult.fail(w, no);
        if (sec.mobility() < 1) return CommandResult.fail(w, s.sector() + " has no mobility to get the goods to the quay");   // KNOWN sell.c:93, none spent
        // KNOWN sell.c:120 military_control: a conquered people you do not hold down will not hand their goods over
        if (sec.occupied() && sec.stock().get(com.mil) * 10 < sec.stock().get(com.civ)) return CommandResult.fail(w, "you do not control " + s.sector() + ": too few soldiers among a conquered people");
        if (!com.has(s.commodity())) return CommandResult.fail(w, "unknown commodity: " + s.commodity());
        if (mc.unsellable().contains(s.commodity())) return CommandResult.fail(w, s.commodity() + " cannot be sold");
        if (!(s.price() > 0) || s.price() > mc.maxPrice()) return CommandResult.fail(w, "the price is a unit's, more than $0 and at most " + money(mc.maxPrice()));
        int ci = com.index(s.commodity());
        double have = Math.floor(sec.stock().get(ci));
        if (s.qty() != Math.rint(s.qty())) return CommandResult.fail(w, "sell a whole number of " + s.commodity());
        double n = s.qty() >= 0 ? Math.min(s.qty(), have) : have + s.qty();   // KNOWN: a negative number keeps that many
        if (n < 1) return CommandResult.fail(w, s.sector() + " has " + q(have) + " " + s.commodity() + (s.qty() < 0 ? ", no more than the " + q(-Math.ceil(s.qty())) + " to keep" : "") + "; nothing to sell");
        long now = w.updateNumber();
        MarketLot lot = new MarketLot(w.nextLotId(), c.id(), ci, n, s.price(), MarketLot.NOBODY, s.sector(), null, now, now + mc.delayUpdates());
        World next = w.withSector(sec.withStock(sec.stock().plus(ci, -n))).withLot(lot);
        return new CommandResult(next, null, 0, "lot " + lot.id() + ": " + q(n) + " " + s.commodity() + " from " + s.sector() + " at " + money(s.price())
                + " a unit; if anybody bids, it sells to the highest after " + mc.delayUpdates() + " updates");
    }

    /**
     * KNOWN buy.c: a bid on somebody else's lot, beating its price by at least {@code min_raise} a unit, from a country
     * that could pay for it on top of every other lot it is winning, into a harbour or warehouse of its own with the
     * room. A new high bid close to the sale gives the others time to answer (the original's last five minutes).
     */
    static CommandResult buy(GameConfig cfg, Commodities com, World w, Country c, Command.Buy b, Room room) {
        MarketCfg mc = cfg(cfg);
        if (mc == null) return CommandResult.fail(w, "this game has no market");
        MarketLot lot = w.lot(b.lot());
        if (lot == null) return CommandResult.fail(w, "no lot " + b.lot() + " on the market");
        if (lot.owner() == c.id()) return CommandResult.fail(w, "lot " + lot.id() + " is yours; reset it to change its price");
        double floor = lot.price() + mc.minRaise();
        if (b.price() > mc.maxPrice()) return CommandResult.fail(w, "no unit sells for more than " + money(mc.maxPrice()));
        if (!(b.price() >= floor - 1e-9)) return CommandResult.fail(w, "the bid is a unit's and must be at least " + money(floor) + " (" + money(lot.price()) + " now)");
        double cost = b.price() * lot.amount() * mc.buyTax();
        if (c.cash() < cost) return CommandResult.fail(w, "it would cost " + money(cost) + " and you have " + money(c.cash()));
        double owed = 0;
        for (MarketLot o : w.market()) if (o.bidder() == c.id() && o.id() != lot.id()) owed += o.price() * o.amount() * mc.buyTax();
        if (c.cash() - owed < cost) return CommandResult.fail(w, "you are already the high bidder for " + money(owed) + " of goods; with this you could not pay for them all (" + money(c.cash()) + ")");
        if (b.dest() == null || !w.inBounds(b.dest())) return CommandResult.fail(w, "deliver it where?");
        Sector to = w.sector(b.dest());
        String no = notAMarketSector(cfg, mc, to, c.id(), b.dest());
        if (no != null) return CommandResult.fail(w, no);
        double space = room.roomFor(to, lot.commodity());
        if (space < lot.amount()) return CommandResult.fail(w, b.dest() + " has room for " + q(space) + " more " + com.id(lot.commodity()) + "; the lot is " + q(lot.amount()));
        long now = w.updateNumber();
        long settles = lot.settles();
        if (lot.bidder() != c.id() && settles - now <= mc.snipeUpdates()) settles = now + mc.snipeUpdates() + 1;
        World next = w.withLot(lot.withBid(c.id(), b.price(), b.dest(), settles));
        return new CommandResult(next, null, 0, "high bid on lot " + lot.id() + ": " + money(b.price()) + " a unit for " + q(lot.amount()) + " " + com.id(lot.commodity())
                + " (" + money(cost) + "), to " + b.dest() + "; it sells in " + (settles - now) + (settles - now == 1 ? " update" : " updates") + " unless someone bids higher");
    }

    /** KNOWN rese.c: an unbid lot of yours, cheaper (its clock starts again), or with 0 or less, back where it came from. */
    static CommandResult reset(GameConfig cfg, Commodities com, World w, Country c, Command.ResetLot r) {
        MarketCfg mc = cfg(cfg);
        if (mc == null) return CommandResult.fail(w, "this game has no market");
        MarketLot lot = w.lot(r.lot());
        if (lot == null || lot.owner() != c.id()) return CommandResult.fail(w, "no lot " + r.lot() + " of yours");
        if (lot.bid()) return CommandResult.fail(w, "lot " + lot.id() + " has a bid on it; it cannot be changed now");
        long now = w.updateNumber();
        if (r.price() > 0) {
            if (r.price() >= lot.price()) return CommandResult.fail(w, "a new price must be lower than " + money(lot.price()));
            return new CommandResult(w.withLot(lot.withPrice(r.price(), now, now + mc.delayUpdates())), null, 0,
                    "lot " + lot.id() + " now " + money(r.price()) + " a unit; it sells after " + mc.delayUpdates() + " updates if anybody bids");
        }
        Sector back = w.sector(lot.from());
        String no = notAMarketSector(cfg, mc, back, c.id(), lot.from());
        if (no != null) return CommandResult.fail(w, "lot " + lot.id() + " cannot go back: " + no);
        World next = w.withoutLot(lot.id()).withSector(back.withStock(back.stock().plus(lot.commodity(), lot.amount())));
        return new CommandResult(next, null, 0, "lot " + lot.id() + " withdrawn: " + q(lot.amount()) + " " + com.id(lot.commodity()) + " back in " + lot.from());
    }

    /** How much more of a commodity a sector can take, as the move command measures it. */
    interface Room { double roomFor(Sector to, int ci); }
}
