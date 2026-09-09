package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates and applies one command between updates. The single code path for humans,
 * agents, console and panels. Rejections carry a message the issuer sees; nothing is
 * partially applied.
 */
public final class CommandExecutor {
    private final GameConfig cfg;
    private final Commodities com;

    public CommandExecutor(GameConfig cfg) { this.cfg = cfg; this.com = Commodities.of(cfg); }

    public CommandResult execute(World w, int countryId, Command cmd) {
        if (countryId < 0 || countryId >= w.countries().size()) return CommandResult.fail(w, "no such country");
        Country c = w.country(countryId);
        double cost = cfg.economy().btu().cost(cmd.verb());
        if (c.btu() < cost) return CommandResult.fail(w, "not enough BTUs: need " + cost + ", have " + fmt(c.btu()));

        CommandResult r = switch (cmd) {
            case Command.BreakSanctuary b -> breakSanctuary(w, c);
            case Command.Designate d -> designate(w, c, d);
            case Command.Threshold t -> threshold(w, c, t);
            case Command.Distribute d -> distribute(w, c, d);
            case Command.Deliver d -> deliver(w, c, d);
            case Command.BuildShip b -> buildShip(w, c, b);
            case Command.Sail s -> sail(w, c, s);
            case Command.Load l -> load(w, c, l);
            case Command.Unload u -> unload(w, c, u);
            case Command.Lane l -> lane(w, c, l);
            case Command.Scrap s -> scrap(w, c, s);
            case Command.Move m -> move(w, c, m);
            case Command.Explore e -> explore(w, c, e);
            case Command.BuildRoad br -> buildRoad(w, c, br);
            case Command.BuildRail bl -> buildRail(w, c, bl);
            case Command.RailShip rs -> railShip(w, c, rs);
        };
        if (!r.ok()) return r;
        World next = r.world();
        Country nc = next.country(countryId);
        next = next.withCountry(nc.withBtu(nc.btu() - cost));
        return new CommandResult(next, null, cost, r.info());
    }

    private CommandResult breakSanctuary(World w, Country c) {
        if (!c.inSanctuary()) return CommandResult.fail(w, "not in sanctuary");
        World next = w;
        for (Sector s : w.ownedBy(c.id())) next = next.withSector(s.withSanctuary(false));
        return new CommandResult(next.withCountry(c.withSanctuary(false)), null, 0);
    }

    private CommandResult designate(World w, Country c, Command.Designate d) {
        Sector s = owned(w, c, d.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + d.sector());
        if (!cfg.hasSectorType(d.type())) return CommandResult.fail(w, "unknown designation: " + d.type());
        SectorTypeCfg t = cfg.sectorType(d.type());
        if (t.hasFlag("no_designate")) return CommandResult.fail(w, "cannot designate a sector as " + d.type());
        if (t.minTechOr0() > c.levels().tech()) return CommandResult.fail(w, d.type() + " requires tech " + t.minTechOr0());
        if (t.terrainRequired() != null && !t.terrainRequired().contains(s.terrain().id()))
            return CommandResult.fail(w, d.type() + " requires terrain " + t.terrainRequired());
        if (t.hasFlag("coastal_required") && !coastal(w, s)) return CommandResult.fail(w, d.type() + " must be coastal");
        if (s.designation().equals(d.type())) return CommandResult.fail(w, "already " + d.type());
        double eff = redesignatedEfficiency(s.designation(), d.type(), s.efficiency());
        World next = w.withSector(s.withDesignation(d.type(), eff));
        if (t.hasFlag("one_per_country_active")) next = next.withCountry(c.withCapital(s.at()));
        return new CommandResult(next, null, 0);
    }

    /** economy.efficiency.redesignate: exact pair (with * wildcards), then same category, then default. */
    double redesignatedEfficiency(String from, String to, double eff) {
        EconomyCfg.EfficiencyCfg.RedesignateCfg r = cfg.economy().efficiency().redesignate();
        Double keep = null;
        if (r.keepFractionByPair() != null) {
            for (String key : new String[] {from + "->" + to, from + "->*", "*->" + to})
                if (r.keepFractionByPair().containsKey(key)) { keep = r.keepFractionByPair().get(key); break; }
        }
        if (keep == null) {
            String fc = cfg.sectorType(from).category(), tc = cfg.sectorType(to).category();
            keep = fc.equals(tc) && !fc.equals("special") ? r.keepFractionSameCategory() : r.keepFractionDefault();
        }
        return Math.max(r.minEfficiencyAfter(), eff * keep);
    }

    private CommandResult threshold(World w, Country c, Command.Threshold t) {
        Sector s = owned(w, c, t.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + t.sector());
        if (!com.has(t.commodity())) return CommandResult.fail(w, "unknown commodity: " + t.commodity());
        double[] th = s.thresholds().clone();
        th[com.index(t.commodity())] = t.amount() < 0 ? Double.NaN : t.amount();
        return new CommandResult(w.withSector(s.withThresholds(th)), null, 0);
    }

    /** A standing order; validated now, executed at every update by the flow step. Issue #45. */
    private CommandResult deliver(World w, Country c, Command.Deliver d) {
        Sector s = owned(w, c, d.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + d.sector());
        if (!com.has(d.commodity())) return CommandResult.fail(w, "unknown commodity: " + d.commodity());
        int ci = com.index(d.commodity());
        if (d.dir() == null) return new CommandResult(w.withSector(s.withDeliver(s.deliver().without(ci))), null, 0, s.deliver().has(ci) ? "delivery of " + d.commodity() + " from " + d.sector() + " cleared" : "no delivery of " + d.commodity() + " was set at " + d.sector());
        if (d.dir() < 0 || d.dir() > 5) return CommandResult.fail(w, "direction is e, ne, nw, w, sw or se");
        if (d.threshold() < 0) return CommandResult.fail(w, "threshold must be 0 or more");
        Coord to = Hex.normalise(w, Hex.stepRaw(s.at(), d.dir()));
        if (to == null) return CommandResult.fail(w, "nothing lies " + Hex.dirName(d.dir()) + " of " + d.sector());
        Sector t = w.sector(to);
        String info = t.owner() != c.id() ? "noted, but nothing moves until you own " + to
                    : !t.terrain().isLand() ? "noted, but " + to + " is sea; nothing will move"
                    : d.commodity() + " above " + fmt(d.threshold()) + " goes " + Hex.dirName(d.dir()) + " to " + to + " every update";
        return new CommandResult(w.withSector(s.withDeliver(s.deliver().with(ci, d.dir(), d.threshold()))), null, 0, info);
    }

    // ---- ships (issue #56) ----
    private Ship myShip(World w, Country c, long id) { Ship s = w.ship(id); return s != null && s.owner() == c.id() ? s : null; }
    private boolean harborOf(World w, Country c, Sector s) { return s != null && s.owner() == c.id() && cfg.sectorType(s.designation()).hasFlag("builds_ships"); }

    private CommandResult buildShip(World w, Country c, Command.BuildShip b) {
        var sc = cfg.units().ships();
        if (sc == null) return CommandResult.fail(w, "ships are not enabled in this world");
        Sector h = owned(w, c, b.harbor());
        if (h == null) return CommandResult.fail(w, "you do not own " + b.harbor());
        if (!harborOf(w, c, h)) return CommandResult.fail(w, b.harbor() + " is not a harbour");
        if (h.efficiency() < sc.harborMinEfficiency()) return CommandResult.fail(w, "the harbour at " + b.harbor() + " is " + fmt(h.efficiency()) + "%; it needs " + fmt(sc.harborMinEfficiency()) + "% to lay a hull");
        if (b.cls() == null || !sc.hasClass(b.cls())) return CommandResult.fail(w, "unknown ship class: " + b.cls());
        var cls = sc.shipClass(b.cls());
        if (c.levels().tech() < cls.techRequired()) return CommandResult.fail(w, cls.name() + " needs tech " + cls.techRequired() + "; you have " + fmt(c.levels().tech()));
        Stocks st = h.stock(); double cash = 0;
        for (var e : cls.buildOrEmpty().entrySet()) {
            if (e.getKey().equals("cash")) { cash = e.getValue(); continue; }
            int ci = com.index(e.getKey());
            if (st.get(ci) < e.getValue()) return CommandResult.fail(w, "the harbour needs " + fmt(e.getValue()) + " " + e.getKey() + " for a " + cls.name() + "; it has " + fmt(st.get(ci)));
            st = st.plus(ci, -e.getValue());
        }
        if (c.cash() < cash) return CommandResult.fail(w, "a " + cls.name() + " costs $" + fmt(cash) + "; you have $" + fmt(c.cash()));
        long id = w.nextShipId();
        Ship ship = new Ship(id, c.id(), cls.id(), b.name() == null ? "" : b.name().trim(), h.at(), sc.startEfficiency(), Stocks.zero(com.size()), null, null, w.updateNumber(), "laid down");
        List<Ship> ships = new ArrayList<>(w.ships()); ships.add(ship);
        World next = w.withSector(h.withStock(st)).withCountry(c.withCash(c.cash() - cash)).withShips(ships, id + 1);
        return new CommandResult(next, null, 0, cls.name() + " #" + id + " laid down at " + b.harbor() + " at " + fmt(sc.startEfficiency()) + "%; it fits out while docked");
    }

    private CommandResult sail(World w, Country c, Command.Sail s) {
        Ship ship = myShip(w, c, s.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + s.ship() + " of yours");
        if (ship.lane() != null) return CommandResult.fail(w, "ship #" + s.ship() + " is on a lane; clear it first");
        if (s.dest() == null) return new CommandResult(w.withShip(ship.withDest(null)), null, 0, "ship #" + s.ship() + " holds position");
        if (!w.inBounds(s.dest())) return CommandResult.fail(w, "out of bounds: " + s.dest());
        List<Coord> path = org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), ship.at(), s.dest());
        if (path == null) return CommandResult.fail(w, "no sea route from " + ship.at() + " to " + s.dest() + " (sea and your harbours only)");
        var cls = cfg.units().ships().shipClass(ship.cls());
        double perUpdate = Math.max(0, Math.floor(cls.speed() * ship.efficiency() / 100.0));
        String eta = perUpdate <= 0 ? "it cannot sail until it is fitter" : "about " + (int) Math.ceil((path.size() - 1) / perUpdate) + " update(s)";
        return new CommandResult(w.withShip(ship.withDest(s.dest())), null, 0, "ship #" + s.ship() + " sails for " + s.dest() + ": " + (path.size() - 1) + " hexes, " + eta);
    }

    private CommandResult load(World w, Country c, Command.Load l) {
        Ship ship = myShip(w, c, l.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + l.ship() + " of yours");
        Sector h = w.sector(ship.at());
        if (!harborOf(w, c, h)) return CommandResult.fail(w, "ship #" + l.ship() + " is not in one of your harbours");
        if (!com.has(l.commodity())) return CommandResult.fail(w, "unknown commodity: " + l.commodity());
        int ci = com.index(l.commodity());
        var cls = cfg.units().ships().shipClass(ship.cls());
        if (!org.hastingtx.empire.engine.update.steps.ShipStep.carries(new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0), cls, ci)) return CommandResult.fail(w, "a " + cls.name() + " cannot carry " + l.commodity());
        double room = cls.hold() - ship.load();
        double q = Math.min(l.qty(), Math.min(room, h.stock().get(ci)));
        if (l.qty() <= 0) return CommandResult.fail(w, "quantity must be positive");
        if (q <= 0) return CommandResult.fail(w, room <= 0 ? "the hold is full" : "no " + l.commodity() + " in the harbour");
        World next = w.withSector(h.withStock(h.stock().plus(ci, -q))).withShip(ship.withStock(ship.stock().plus(ci, q)));
        return new CommandResult(next, null, 0, "loaded " + fmt(q) + " " + l.commodity() + (q < l.qty() ? " (" + (room < l.qty() ? "hold full" : "all there was") + ")" : ""));
    }

    private CommandResult unload(World w, Country c, Command.Unload u) {
        Ship ship = myShip(w, c, u.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + u.ship() + " of yours");
        Sector h = w.sector(ship.at());
        if (!harborOf(w, c, h)) return CommandResult.fail(w, "ship #" + u.ship() + " is not in one of your harbours");
        if (!com.has(u.commodity())) return CommandResult.fail(w, "unknown commodity: " + u.commodity());
        int ci = com.index(u.commodity());
        org.hastingtx.empire.engine.update.Ctx ctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        double room = com.isPerson(ci) ? Math.max(0, ctx.maxPopulation(h) - (h.stock().get(com.civ) + h.stock().get(com.uw))) : Math.max(0, ctx.capacity(h, ci) - h.stock().get(ci));
        double q = Math.min(u.qty(), Math.min(room, ship.stock().get(ci)));
        if (u.qty() <= 0) return CommandResult.fail(w, "quantity must be positive");
        if (q <= 0) return CommandResult.fail(w, ship.stock().get(ci) <= 0 ? "no " + u.commodity() + " aboard" : "no room in the harbour");
        World next = w.withSector(h.withStock(h.stock().plus(ci, q))).withShip(ship.withStock(ship.stock().plus(ci, -q)));
        return new CommandResult(next, null, 0, "unloaded " + fmt(q) + " " + u.commodity());
    }

    private CommandResult lane(World w, Country c, Command.Lane l) {
        Ship ship = myShip(w, c, l.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + l.ship() + " of yours");
        if (l.from() == null) return new CommandResult(w.withShip(ship.withLane(null).withDest(null)), null, 0, "ship #" + l.ship() + " leaves its lane and holds position");
        if (!harborOf(w, c, w.sector(l.from()))) return CommandResult.fail(w, l.from() + " is not one of your harbours");
        if (!harborOf(w, c, w.sector(l.to()))) return CommandResult.fail(w, l.to() + " is not one of your harbours");
        if (l.from().equals(l.to())) return CommandResult.fail(w, "a lane needs two different harbours");
        if (org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), l.from(), l.to()) == null) return CommandResult.fail(w, "no sea route between " + l.from() + " and " + l.to());
        if (org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), ship.at(), l.from()) == null) return CommandResult.fail(w, "ship #" + l.ship() + " has no sea route to " + l.from());
        var cls = cfg.units().ships().shipClass(ship.cls());
        List<Integer> cargo = new ArrayList<>();
        org.hastingtx.empire.engine.update.Ctx ctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        if (l.cargo() != null) for (String k : l.cargo()) {
            if (!com.has(k)) return CommandResult.fail(w, "unknown commodity: " + k);
            if (!org.hastingtx.empire.engine.update.steps.ShipStep.carries(ctx, cls, com.index(k))) return CommandResult.fail(w, "a " + cls.name() + " cannot carry " + k);
            cargo.add(com.index(k));
        }
        if (cls.carriesOrEmpty().isEmpty()) return CommandResult.fail(w, "a " + cls.name() + " carries no cargo");
        Ship.Lane lane = new Ship.Lane(l.from(), l.to(), cargo, false);
        return new CommandResult(w.withShip(ship.withLane(lane).withDest(l.from())), null, 0, "ship #" + l.ship() + " runs " + l.from() + " → " + l.to() + " carrying " + (cargo.isEmpty() ? "whatever it can" : String.join(", ", l.cargo())) + "; heading to " + l.from() + " to load");
    }

    private CommandResult scrap(World w, Country c, Command.Scrap s) {
        Ship ship = myShip(w, c, s.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + s.ship() + " of yours");
        Sector h = w.sector(ship.at());
        if (!harborOf(w, c, h)) return CommandResult.fail(w, "ship #" + s.ship() + " must be in one of your harbours to be scrapped");
        Stocks st = h.stock();
        for (int ci = 0; ci < com.size(); ci++) st = st.plus(ci, ship.stock().get(ci));   // the hold goes ashore (capacity applies at the update)
        return new CommandResult(w.withSector(h.withStock(st)).withoutShip(ship.id()), null, 0, "ship #" + s.ship() + " scrapped at " + h.at());
    }

    private CommandResult distribute(World w, Country c, Command.Distribute d) {
        Sector s = owned(w, c, d.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + d.sector());
        if (d.center() != null) {
            Sector ctr = owned(w, c, d.center());
            if (ctr == null) return CommandResult.fail(w, "you do not own the centre " + d.center());
            if (!ctr.terrain().isLand()) return CommandResult.fail(w, "centre must be land");
        }
        return new CommandResult(w.withSector(s.withDistCenter(d.center())), null, 0);
    }

    /**
     * Immediate, as in the original: the goods land now and every sector entered pays its mobility
     * now. If mobility along the route is short, the quantity is capped to what fits; the rest stays
     * at the source. Richard, 2026-09-08. Distribution and rail keep the update-time range-and-hold rule.
     */
    private CommandResult move(World w, Country c, Command.Move m) {
        Sector from = owned(w, c, m.from());
        if (from == null) return CommandResult.fail(w, "you do not own " + m.from());
        Sector to = owned(w, c, m.to());
        if (to == null) return CommandResult.fail(w, "you do not own " + m.to());
        if (!com.has(m.commodity())) return CommandResult.fail(w, "unknown commodity: " + m.commodity());
        if (m.qty() <= 0) return CommandResult.fail(w, "quantity must be positive");
        if (c.inSanctuary()) return CommandResult.fail(w, "break sanctuary first");
        int ci = com.index(m.commodity());
        double have = from.stock().get(ci);
        if (have < m.qty()) return CommandResult.fail(w, "only " + fmt(have) + " " + m.commodity() + " in " + m.from());
        if (m.from().equals(m.to())) return CommandResult.fail(w, "that is where it already is");
        org.hastingtx.empire.engine.update.Ctx ctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        java.util.List<Coord> path = org.hastingtx.empire.engine.update.steps.FlowStep.path(ctx, m.from(), m.to(), c.id(), cfg.distribution());
        if (path == null) return CommandResult.fail(w, "no route through your territory from " + m.from() + " to " + m.to());
        int reach = (int) Math.floor(cfg.economy().mobility().manualMoveMaxSectorsPerUpdate().eval(c.levels().tech()));
        if (path.size() - 1 > reach) return CommandResult.fail(w, m.to() + " is " + (path.size() - 1) + " sectors away; your reach is " + reach);
        double weight = ctx.weightLeaving(ci, from);
        boolean srcPays = cfg.distribution().sourcePays();
        double moving = m.qty(), totalUnit = 0;
        double[] unit = new double[path.size()];
        Coord choke = path.get(1);
        for (int h = 1; h < path.size(); h++) {
            Sector t = w.sector(path.get(h));
            unit[h] = weight * ctx.moveCostInto(t);
            totalUnit += unit[h];
            if (!srcPays && unit[h] > 0 && t.mobility() / unit[h] < moving) { moving = t.mobility() / unit[h]; choke = t.at(); }
        }
        if (srcPays && totalUnit > 0) moving = Math.min(moving, from.mobility() / totalUnit);
        // people never move into a sector that cannot hold them (issue #48): the update would truncate them
        boolean people = ci == com.civ || ci == com.uw;
        double room = people ? Math.max(0, ctx.maxPopulation(to) - (to.stock().get(com.civ) + to.stock().get(com.uw))) : Double.POSITIVE_INFINITY;
        if (people && room < moving) moving = room;
        if (people && room <= 0) return CommandResult.fail(w, m.to() + " is full: " + fmt(to.stock().get(com.civ) + to.stock().get(com.uw)) + " people at a limit of " + fmt(ctx.maxPopulation(to)));
        moving = Math.floor(moving * 1000) / 1000;
        if (moving <= 0) return CommandResult.fail(w, srcPays
                ? "no mobility in " + m.from() + " (has " + fmt(from.mobility()) + "; the route costs " + fmt(totalUnit) + " per unit)"
                : "no mobility along the route (" + choke + " has " + fmt(w.sector(choke).mobility()) + ")");
        World next = w;
        if (srcPays) {
            next = next.withSector(from.withMobility(Math.max(0, from.mobility() - moving * totalUnit)));
        } else {
            for (int h = 1; h < path.size(); h++) {
                Sector t = next.sector(path.get(h));
                next = next.withSector(t.withMobility(Math.max(0, t.mobility() - moving * unit[h])));
            }
        }
        Sector src = next.sector(m.from()), dst = next.sector(m.to());
        next = next.withSector(src.withStock(src.stock().plus(ci, -moving)));
        next = next.withSector(dst.withStock(dst.stock().plus(ci, moving)));
        String info = moving < m.qty() - 1e-9 ? "moved " + fmt(moving) + " of " + fmt(m.qty()) + " " + m.commodity() + " — " + (people && moving >= room - 1e-9 ? m.to() + " has room for no more" : srcPays ? "mobility in " + m.from() + " ran out" : "mobility along the route ran out") + "; the rest stayed in " + m.from()
                                              : "moved " + fmt(moving) + " " + m.commodity() + " to " + m.to();
        return new CommandResult(next, null, 0, info);
    }

    private CommandResult buildRoad(World w, Country c, Command.BuildRoad r) {
        Sector s = owned(w, c, r.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + r.sector());
        if (!s.terrain().isLand()) return CommandResult.fail(w, "cannot pave the sea");
        if (r.targetLevel() < 0 || r.targetLevel() > 100) return CommandResult.fail(w, "road level is 0..100");
        // Above the terrain's cap the order is the cap, not a refusal (Richard 2026-09-08, issue #43): "road * 100" paves every sector as far as it can go.
        Double cap = cfg.infrastructure().road().maxLevelByTerrain().get(s.terrain().id());
        double target = cap != null && r.targetLevel() > cap ? cap : r.targetLevel();
        String info = target < r.targetLevel() ? "road ordered to " + Math.round(target) + ", the " + s.terrain().id() + " cap (" + Math.round(r.targetLevel()) + " asked)" : null;
        return new CommandResult(w.withSector(s.withRoadTarget(target)), null, 0, info);
    }

    private CommandResult buildRail(World w, Country c, Command.BuildRail r) {
        Sector s = owned(w, c, r.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + r.sector());
        if (!s.terrain().isLand()) return CommandResult.fail(w, "cannot lay rail on the sea");
        var rail = cfg.infrastructure().rail();
        if (c.levels().tech() < rail.techRequired()) return CommandResult.fail(w, "rail needs tech " + rail.techRequired() + "; you have " + fmt(c.levels().tech()));
        if (r.targetLevel() < 0 || r.targetLevel() > 100) return CommandResult.fail(w, "rail level is 0..100");
        Double cap = rail.maxLevelByTerrain().get(s.terrain().id());
        double target = cap != null && r.targetLevel() > cap ? cap : r.targetLevel();
        String info = target < r.targetLevel() ? "rail ordered to " + Math.round(target) + ", the " + s.terrain().id() + " cap (" + Math.round(r.targetLevel()) + " asked)" : null;
        return new CommandResult(w.withSector(s.withRailTarget(target)), null, 0, info);
    }

    /** Validated at issue time: both ends are working depots and a contiguous line joins them, or the reply names where it breaks. */
    private CommandResult railShip(World w, Country c, Command.RailShip r) {
        Sector from = owned(w, c, r.from());
        if (from == null) return CommandResult.fail(w, "you do not own " + r.from());
        Sector to = owned(w, c, r.to());
        if (to == null) return CommandResult.fail(w, "you do not own " + r.to());
        if (!com.has(r.commodity())) return CommandResult.fail(w, "unknown commodity: " + r.commodity());
        if (r.qty() <= 0) return CommandResult.fail(w, "quantity must be positive");
        if (r.from().equals(r.to())) return CommandResult.fail(w, "that is where it already is");
        org.hastingtx.empire.engine.update.Ctx ctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        int fi = w.index(r.from()), ti = w.index(r.to());
        if (!ctx.isDepot(fi)) return CommandResult.fail(w, r.from() + " is not a working depot (needs the depot designation at 60%+ and rail of at least " + fmt(cfg.infrastructure().rail().minLevelToCarry()) + ")");
        if (!ctx.isDepot(ti)) return CommandResult.fail(w, r.to() + " is not a working depot");
        int ci = com.index(r.commodity());
        double committed = 0;
        for (var o : w.pendingRail()) if (o.owner() == c.id() && o.from().equals(r.from()) && o.commodity() == ci) committed += o.qty();
        if (from.stock().get(ci) - committed < r.qty()) return CommandResult.fail(w, "only " + fmt(from.stock().get(ci) - committed) + " " + r.commodity() + " uncommitted in " + r.from());
        if (ctx.railPath(r.from(), r.to(), c.id()) == null) {
            java.util.Set<Integer> reach = ctx.railReach(r.from(), c.id());
            int best = fi, bestD = Integer.MAX_VALUE;
            for (int i : reach) { int d = org.hastingtx.empire.engine.geo.Hex.distance(w, w.sectors().get(i).at(), r.to()); if (d < bestD) { bestD = d; best = i; } }
            return CommandResult.fail(w, "no rail line from " + r.from() + " to " + r.to() + ": the track ends at " + w.sectors().get(best).at());
        }
        java.util.List<org.hastingtx.empire.engine.model.RailOrder> next = new java.util.ArrayList<>(w.pendingRail());
        next.add(new org.hastingtx.empire.engine.model.RailOrder(c.id(), r.from(), r.to(), ci, r.qty(), w.updateNumber()));
        return new CommandResult(w.withPendingRail(next), null, 0, "train scheduled: " + fmt(r.qty()) + " " + r.commodity() + " " + r.from() + " → " + r.to() + " at the update");
    }

    private CommandResult explore(World w, Country c, Command.Explore e) {
        if (c.inSanctuary()) return CommandResult.fail(w, "break sanctuary first");
        Sector from = owned(w, c, e.from());
        if (from == null) return CommandResult.fail(w, "you do not own " + e.from());
        if (!w.inBounds(e.to())) return CommandResult.fail(w, "out of bounds: " + e.to());
        Sector to = w.sector(e.to());
        if (!Hex.neighbours(w, e.from()).contains(e.to())) return CommandResult.fail(w, e.to() + " is not adjacent to " + e.from());
        if (!to.terrain().isLand()) return CommandResult.fail(w, e.to() + " is ocean");
        if (to.sanctuary()) return CommandResult.fail(w, e.to() + " is another country's sanctuary; no one may enter until they break sanctuary");
        if (to.owned()) return CommandResult.fail(w, e.to() + " is already owned");
        if (e.civs() < 1) return CommandResult.fail(w, "need at least one civilian");
        if (from.stock().get(com.civ) < e.civs()) return CommandResult.fail(w, "only " + fmt(from.stock().get(com.civ)) + " civilians in " + e.from());
        // GUESS: original charged the source sector's mobility for the walk. Cost = civs × cost into target.
        org.hastingtx.empire.engine.update.Ctx ectx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        double mobCost = e.civs() * ectx.weightLeaving(com.civ, from) * moveCostInto(to);
        if (from.mobility() < mobCost) return CommandResult.fail(w, "need " + fmt(mobCost) + " mobility in " + e.from() + ", have " + fmt(from.mobility()));
        World next = w.withSector(from.withMobility(from.mobility() - mobCost).withStock(from.stock().plus(com.civ, -e.civs())));
        next = next.withSector(to.withOwner(c.id()).withStock(to.stock().plus(com.civ, e.civs())));
        return new CommandResult(next, null, 0);
    }

    private double moveCostInto(Sector s) {
        EconomyCfg.MobilityCfg m = cfg.economy().mobility();
        double base = m.moveCostByTerrain().getOrDefault(s.terrain().id(), Double.POSITIVE_INFINITY);
        return base * m.efficiencyDiscount().eval(s.efficiency()) * cfg.infrastructure().road().mobilityDiscountCurve().eval(s.roadLevel());
    }

    private boolean coastal(World w, Sector s) {
        for (Coord n : Hex.neighbours(w, s.at())) if (!w.sector(n).terrain().isLand()) return true;
        return false;
    }

    private static Sector owned(World w, Country c, Coord at) {
        if (at == null || !w.inBounds(at)) return null;
        Sector s = w.sector(at);
        return s.owner() == c.id() ? s : null;
    }

    private static String fmt(double d) { return String.format(java.util.Locale.ROOT, "%.1f", d); }
}
