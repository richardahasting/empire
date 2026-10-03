package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.MarketCfg;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ledger;

import java.util.ArrayList;
import java.util.List;

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
        double owed = owed(w, c.id(), mc) - (lot.bidder() == c.id() ? lot.price() * lot.amount() * mc.buyTax() : 0);
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

    /** What a country would owe if every lot it holds the high bid on sold to it now (KNOWN buy.c:115-135, trad.c:245). */
    static double owed(World w, int who, MarketCfg mc) {
        double owed = 0;
        for (MarketLot o : w.market()) if (o.bidder() == who) owed += o.price() * o.amount() * mc.buyTax();
        for (TradeLot o : w.trades()) if (o.bidder() == who) owed += o.price();
        return owed;
    }

    // ------------------------------------------------------------------------- ships, planes and units (set, trade)

    /** "ship", "plane" or "unit" ("land" too, the original's word); null for anything else. */
    static String kind(String k) {
        if (k == null) return null;
        return switch (k.toLowerCase(java.util.Locale.ROOT)) {
            case "ship", "ships", "s" -> TradeLot.SHIP;
            case "plane", "planes", "p" -> TradeLot.PLANE;
            case "unit", "units", "land", "l", "u" -> TradeLot.UNIT;
            default -> null;
        };
    }

    /** Where it is and who owns it, or null if there is no such thing. */
    private record Item(int owner, Coord at, String label, double civ, long aboard) {}

    private static Item item(Commodities com, World w, String kind, long id) {
        return switch (kind) {
            case TradeLot.SHIP -> { Ship s = w.ship(id); yield s == null ? null : new Item(s.owner(), s.at(), "ship #" + id + " (" + s.cls().replace('_', ' ') + ")", s.stock().get(com.civ), 0); }
            case TradeLot.PLANE -> { Plane p = w.plane(id); yield p == null ? null : new Item(p.owner(), p.at(), "plane #" + id + " (" + p.cls().replace('_', ' ') + ")", 0, p.ship()); }
            default -> { LandUnit u = w.unit(id); yield u == null ? null : new Item(u.owner(), u.at(), "unit #" + id + " (" + u.cls().replace('_', ' ') + ")", u.stock().get(com.civ), u.ship()); }
        };
    }

    /**
     * KNOWN set.c: a price on each, in whole dollars; 0 takes it off. All of them must be yours and in a sector you
     * control (the original aborted the whole command otherwise), and nobody aboard may be a civilian: people are
     * not for sale (trdsub.c). Setting a price again starts the lot afresh, bids and all, as the original did.
     */
    static CommandResult setPrice(GameConfig cfg, Commodities com, World w, Country c, Command.SetPrice sp) {
        MarketCfg mc = cfg(cfg);
        if (mc == null || !mc.objectTrade()) return CommandResult.fail(w, "this game has no trade in ships, planes and units");
        String kind = kind(sp.kind());
        if (kind == null) return CommandResult.fail(w, "set ship|plane|unit IDS PRICE — got '" + sp.kind() + "'");
        if (sp.items().isEmpty()) return CommandResult.fail(w, "set which " + kind + "?");
        if (sp.price() < 0) return CommandResult.fail(w, "no negative prices");
        if (sp.price() != Math.rint(sp.price())) return CommandResult.fail(w, "a " + kind + "'s price is in whole dollars");
        long now = w.updateNumber();
        List<String> done = new ArrayList<>();
        for (long id : sp.items()) {
            Item it = item(com, w, kind, id);
            if (it == null || it.owner() != c.id()) return CommandResult.fail(w, "no " + kind + " #" + id + " of yours");
            Sector here = w.sector(it.at());
            if (here.owner() == c.id() && here.occupied() && here.stock().get(com.mil) * 10 < here.stock().get(com.civ))
                return CommandResult.fail(w, "you do not control " + it.at() + ", where " + it.label() + " is");
            if (it.civ() >= 1) return CommandResult.fail(w, it.label() + " has civilians aboard; people are not for sale");
            if (it.aboard() != 0) return CommandResult.fail(w, it.label() + " is aboard ship #" + it.aboard() + "; put it ashore first");
            // a lot left from a former owner (captured since) is dead: clear it, so one item never has two lots
            for (TradeLot old : List.copyOf(w.trades())) if (old.kind().equals(kind) && old.item() == id && old.owner() != c.id()) w = w.withoutTrade(old.id());
            TradeLot was = w.onTheBlock(kind, id);
            if (sp.price() == 0) {
                if (was != null) { w = w.withoutTrade(was.id()); done.add(it.label() + " is off the market"); }
                else done.add(it.label() + " was not for sale");
                continue;
            }
            TradeLot lot = new TradeLot(was == null ? w.nextTradeId() : was.id(), c.id(), kind, id, sp.price(), TradeLot.NOBODY, null, now, now + mc.tradeDelayUpdates());
            w = w.withTrade(lot);
            done.add(it.label() + " is lot T" + lot.id() + " at " + money(sp.price()) + (was != null && was.bid() ? " (the bid on it is void)" : ""));
        }
        return new CommandResult(w, null, 0, String.join("; ", done) + (sp.price() > 0 ? "; while it is for sale it stays where it is and does nothing" : ""));
    }

    /**
     * KNOWN trad.c: a bid, in whole dollars, more than the lot's price, that the bidder could pay on top of everything
     * else they are winning; a plane needs an airfield of the bidder's to go to and a unit a headquarters, at 60%+.
     */
    static CommandResult tradeBid(GameConfig cfg, Commodities com, World w, Country c, Command.Trade t) {
        MarketCfg mc = cfg(cfg);
        if (mc == null || !mc.objectTrade()) return CommandResult.fail(w, "this game has no trade in ships, planes and units");
        TradeLot lot = w.trade(t.lot());
        if (lot == null) return CommandResult.fail(w, "no lot T" + t.lot() + " for sale");
        if (lot.owner() == c.id()) return CommandResult.fail(w, "lot T" + lot.id() + " is yours; set its price again to change it");
        Item it = item(com, w, lot.kind(), lot.item());
        if (it == null || it.owner() != lot.owner()) return CommandResult.fail(w, "lot T" + lot.id() + " is no longer for sale");
        if (t.price() != Math.rint(t.price())) return CommandResult.fail(w, "bids are in whole dollars");
        if (!(t.price() > lot.price())) return CommandResult.fail(w, "the bid must be more than " + money(lot.price()));
        if (c.cash() < t.price()) return CommandResult.fail(w, "it would cost " + money(t.price()) + " and you have " + money(c.cash()));
        double owed = owed(w, c.id(), mc) - (lot.bidder() == c.id() ? lot.price() : 0);
        if (c.cash() - owed < t.price()) return CommandResult.fail(w, "you are already the high bidder for " + money(owed) + "; with this you could not pay for it all (" + money(c.cash()) + ")");
        Coord dest = null;
        if (!lot.kind().equals(TradeLot.SHIP)) {
            String flag = lot.kind().equals(TradeLot.PLANE) ? "builds_planes" : "builds_units", place = lot.kind().equals(TradeLot.PLANE) ? "an airfield" : "a headquarters";
            if (t.dest() == null || !w.inBounds(t.dest())) return CommandResult.fail(w, "a " + lot.kind() + " needs " + place + " of yours to go to");
            Sector to = w.sector(t.dest());
            if (to.owner() != c.id() || !cfg.sectorType(to.designation()).hasFlag(flag)) return CommandResult.fail(w, t.dest() + " is not " + place + " of yours");
            if (to.efficiency() < mc.minEfficiency()) return CommandResult.fail(w, t.dest() + " is at " + q(to.efficiency()) + "%; it takes " + q(mc.minEfficiency()) + "%");
            dest = t.dest();
        }
        long now = w.updateNumber(), settles = lot.settles();
        if (lot.bidder() != c.id() && settles - now <= mc.snipeUpdates()) settles = now + mc.snipeUpdates() + 1;
        return new CommandResult(w.withTrade(lot.withBid(c.id(), t.price(), dest, settles)), null, 0,
                "high bid on lot T" + lot.id() + ", " + it.label() + ": " + money(t.price()) + (dest == null ? "; she changes hands where she lies" : ", to " + dest)
                        + "; it sells in " + (settles - now) + (settles - now == 1 ? " update" : " updates") + " unless someone bids higher");
    }

    /**
     * A ship, plane or unit for sale does nothing (KNOWN: navigate, march, load, fly, fire and attack all refuse one on
     * the trading block). Null if the command touches nothing for sale; otherwise why it is refused.
     */
    static String frozen(World w, Command cmd) {
        if (w.trades().isEmpty()) return null;
        List<long[]> ships = new ArrayList<>(), planes = new ArrayList<>(), units = new ArrayList<>();
        switch (cmd) {
            case Command.Sail x -> ships.add(new long[] {x.ship()});
            case Command.Load x -> ships.add(new long[] {x.ship()});
            case Command.Unload x -> ships.add(new long[] {x.ship()});
            case Command.Lane x -> ships.add(new long[] {x.ship()});
            case Command.Fish x -> ships.add(new long[] {x.ship()});
            case Command.Mine x -> ships.add(new long[] {x.ship()});
            case Command.Supply x -> ships.add(new long[] {x.ship()});
            case Command.Fire x -> ships.add(new long[] {x.ship()});
            case Command.Mission x -> { ships.add(new long[] {x.ship()}); if (x.ward() != 0) ships.add(new long[] {x.ward()}); }
            case Command.Land x -> ships.add(new long[] {x.ship()});
            case Command.Scrap x -> ships.add(new long[] {x.ship()});
            case Command.March x -> units.add(new long[] {x.unit()});
            case Command.LoadUnit x -> units.add(new long[] {x.unit()});
            case Command.Board x -> { units.add(new long[] {x.unit()}); if (x.ship() != 0) ships.add(new long[] {x.ship()}); }
            case Command.UnitFire x -> units.add(new long[] {x.unit()});
            case Command.Work x -> units.add(new long[] {x.unit()});
            case Command.Sabotage x -> units.add(new long[] {x.unit()});
            case Command.Incite x -> units.add(new long[] {x.unit()});
            case Command.Attack x -> { for (long u : x.units()) units.add(new long[] {u}); }
            case Command.Bomb x -> { planes.add(new long[] {x.plane()}); for (long e : x.escorts()) planes.add(new long[] {e}); }
            case Command.Recon x -> { planes.add(new long[] {x.plane()}); for (long e : x.escorts()) planes.add(new long[] {e}); }
            case Command.AirMission x -> planes.add(new long[] {x.plane()});
            case Command.Fly x -> { for (long p : x.planes()) planes.add(new long[] {p}); for (long e : x.escorts()) planes.add(new long[] {e}); }
            case Command.Drop x -> { for (long p : x.planes()) planes.add(new long[] {p}); for (long e : x.escorts()) planes.add(new long[] {e}); }
            case Command.Paradrop x -> { for (long p : x.planes()) planes.add(new long[] {p}); for (long e : x.escorts()) planes.add(new long[] {e}); }
            case Command.SweepAir x -> { for (long p : x.planes()) planes.add(new long[] {p}); for (long e : x.escorts()) planes.add(new long[] {e}); }
            case Command.Lay x -> ships.add(new long[] {x.ship()});
            case Command.Launch x -> planes.add(new long[] {x.missile()});
            case Command.LandMine x -> units.add(new long[] {x.unit()});
            default -> { }
        }
        for (long[] s : ships) { TradeLot l = w.onTheBlock(TradeLot.SHIP, s[0]); if (l != null) return "ship #" + s[0] + " is for sale (lot T" + l.id() + "); set ship " + s[0] + " 0 takes it off"; }
        for (long[] p : planes) { TradeLot l = w.onTheBlock(TradeLot.PLANE, p[0]); if (l != null) return "plane #" + p[0] + " is for sale (lot T" + l.id() + "); set plane " + p[0] + " 0 takes it off"; }
        for (long[] u : units) { TradeLot l = w.onTheBlock(TradeLot.UNIT, u[0]); if (l != null) return "unit #" + u[0] + " is for sale (lot T" + l.id() + "); set unit " + u[0] + " 0 takes it off"; }
        return null;
    }

    /** How much more of a commodity a sector can take, as the move command measures it. */
    interface Room { double roomFor(Sector to, int ci); }
}
