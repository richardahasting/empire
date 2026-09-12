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
            case Command.Telegram t -> telegram(w, c, t);
            case Command.Announce a -> announce(w, c, a);
            case Command.Fish f -> fish(w, c, f);
            case Command.Mine m -> mine(w, c, m);
            case Command.Move m -> move(w, c, m);
            case Command.Explore e -> explore(w, c, e);
            case Command.BuildRoad br -> buildRoad(w, c, br);
            case Command.BuildRail bl -> buildRail(w, c, bl);
            case Command.RailShip rs -> railShip(w, c, rs);
            case Command.RailLane rl -> railLane(w, c, rl);
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
        Sector designated = s.withDesignation(d.type(), eff);
        String wired = null;
        if (cfg.distribution().autoWireOn()) { Sector[] box = {designated}; wired = autoWire(w, c, box, t); designated = box[0]; }
        World next = w.withSector(designated);
        if (t.hasFlag("one_per_country_active")) next = next.withCountry(c.withCapital(s.at()));
        return new CommandResult(next, null, 0, wired);
    }

    /**
     * Put a freshly designated sector to work (issue #99): point it at the nearest distribution hub it
     * can actually reach, and give it thresholds from its own sector type.
     *
     * <p>It only ever fills in blanks. A centre or a threshold the player set is left exactly as it is,
     * so re-designating can never undo a deliberate {@code distribute} or {@code thresh}.
     *
     * <p>Returns what it did, for the reply, or null if there was nothing to do.
     */
    private String autoWire(World w, Country c, Sector[] box, SectorTypeCfg t) {
        var aw = cfg.distribution().autoWire();
        Sector s = box[0];
        StringBuilder said = new StringBuilder();

        if (s.distCenter() == null) {
            Coord hub = nearestHub(w, c, s.at());
            if (hub != null) { s = s.withDistCenter(hub); said.append("surplus goes to ").append(hub); }
        }

        // what it makes leaves; what it eats is kept topped up; people and food are wanted everywhere
        java.util.LinkedHashMap<Integer, Double> want = new java.util.LinkedHashMap<>();
        for (String id : t.produces().keySet()) if (com.has(id)) want.put(com.index(id), aw.producedOr0());
        for (String id : t.consumes().keySet()) if (com.has(id)) want.putIfAbsent(com.index(id), aw.consumedOr0());
        want.putIfAbsent(com.civ, aw.civOr0());
        want.putIfAbsent(com.food, aw.foodOr0());

        List<String> set = new ArrayList<>();
        for (var e : want.entrySet()) {
            if (s.hasThreshold(e.getKey())) continue;   // the player already said what they want here
            s = s.withThreshold(e.getKey(), e.getValue());
            set.add(com.id(e.getKey()) + " " + fmt(e.getValue()));
        }
        if (!set.isEmpty()) { if (!said.isEmpty()) said.append("; "); said.append("thresholds ").append(String.join(", ", set)); }

        box[0] = s;
        return said.isEmpty() ? null : said.toString();
    }

    /**
     * The nearest sector of {@code c}'s carrying the {@code distribution_hub} flag that a distribution
     * shipment could actually reach. Nearest by hex distance, ties broken by coordinate order so two
     * equally close warehouses always give the same answer.
     */
    private Coord nearestHub(World w, Country c, Coord from) {
        org.hastingtx.empire.engine.update.Ctx ctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        Coord best = null; int bestD = Integer.MAX_VALUE;
        for (Sector h : w.ownedBy(c.id())) {
            if (h.at().equals(from) || !cfg.sectorType(h.designation()).hasFlag("distribution_hub")) continue;
            int d = Hex.distance(w, from, h.at());
            if (d > bestD || (d == bestD && best != null && h.at().compareTo(best) >= 0)) continue;
            if (org.hastingtx.empire.engine.update.steps.FlowStep.path(ctx, from, h.at(), c.id(), cfg.distribution()) == null) continue;
            best = h.at(); bestD = d;
        }
        return best;
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
        return new CommandResult(w.withSector(s.withThreshold(com.index(t.commodity()), t.amount() < 0 ? Double.NaN : t.amount())), null, 0);
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
        Ship ship = new Ship(id, c.id(), cls.id(), b.name() == null ? "" : b.name().trim(), h.at(), sc.startEfficiency(), Stocks.zero(com.size()), null, null, w.updateNumber(), "laid down", c.levels().tech(), null, null);
        List<Ship> ships = new ArrayList<>(w.ships()); ships.add(ship);
        World next = w.withSector(h.withStock(st)).withCountry(c.withCash(c.cash() - cash)).withShips(ships, id + 1);
        return new CommandResult(next, null, 0, cls.name() + " #" + id + " laid down at " + b.harbor() + " at " + fmt(sc.startEfficiency()) + "%; it fits out while docked");
    }

    private CommandResult sail(World w, Country c, Command.Sail s) {
        Ship ship = myShip(w, c, s.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + s.ship() + " of yours");
        if (ship.lane() != null) return CommandResult.fail(w, "ship #" + s.ship() + " is on a lane; clear it first");
        if (s.dest() == null) return new CommandResult(w.withShip(ship.withDest(null).withMission(null, null)), null, 0, "ship #" + s.ship() + " holds position");
        if (!w.inBounds(s.dest())) return CommandResult.fail(w, "out of bounds: " + s.dest());
        List<Coord> path = org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), ship.at(), s.dest());
        if (path == null) return CommandResult.fail(w, "no sea route from " + ship.at() + " to " + s.dest() + " (sea and your harbours only)");
        var cls = cfg.units().ships().shipClass(ship.cls());
        var sc = cfg.units().ships();
        double perUpdate = sc.range(cls, ship.tech(), ship.efficiency());
        String ended = ship.roaming() ? " (" + ship.mission() + "ing mission ended)" : "";
        int hexes = path.size() - 1;
        ship = ship.withDest(s.dest()).withMission(null, null);

        // A sail happens now (issue #69). The ship goes as far as its own mobility and its tank will
        // carry it, this command, and the rest waits for the update — which is what makes a warship
        // able to answer something it has just seen instead of an update later.
        if (sc.immediateSail()) {
            if (sc.crews() && ship.crew() < cls.crewOr0())
                return new CommandResult(w.withShip(ship), null, 0, "ship #" + s.ship() + " is short-handed and stays at the quay; it will sail when it has a crew" + ended);
            double perHex = sc.fuel() ? cls.fuelPerHexOr0() : 0;
            int byFuel = perHex > 0 ? (int) Math.floor(ship.fuel() / perHex) : hexes;
            // haste is dearer than planning: a hex ordered now costs rushCost, a planned one costs 1
            double rush = sc.rushCost();
            int hops = Math.min(Math.min((int) Math.floor(ship.mobility() / rush), hexes), byFuel);
            if (hops > 0) {
                Coord to = path.get(hops);
                ship = ship.withAt(to).withMobility(ship.mobility() - hops * rush);
                if (perHex > 0) ship = ship.withFuel(ship.fuel() - hops * perHex);
                boolean there = to.equals(s.dest());
                if (there) ship = ship.withDest(null);
                return new CommandResult(w.withShip(ship), null, 0,
                        "ship #" + s.ship() + " sails " + hops + (hops == 1 ? " hex" : " hexes") + " to " + to
                                + (there ? ", arrived" : "; " + (hexes - hops) + " to go, at the update") + ended);
            }
            String why = perHex > 0 && byFuel <= 0 ? "its tank is dry"
                    : perUpdate <= 0 ? "it is too unfit to sail"
                    : "it has no way on it yet";
            return new CommandResult(w.withShip(ship), null, 0,
                    "ship #" + s.ship() + " is bound for " + s.dest() + " (" + hexes + " hexes) but " + why + "; it will start at the update" + ended);
        }

        String eta = perUpdate <= 0 ? "it cannot sail until it is fitter" : "about " + (int) Math.ceil(hexes / perUpdate) + " update(s)";
        return new CommandResult(w.withShip(ship), null, 0, "ship #" + s.ship() + " sails for " + s.dest() + ": " + hexes + " hexes, " + eta + ended);
    }

    /** The harbour, then any dockside warehouse of yours next to it (issue #78). */
    private List<Sector> dockside(World w, Country c, Sector harbor) {
        List<Sector> out = new java.util.ArrayList<>();
        out.add(harbor);
        for (Coord nb : org.hastingtx.empire.engine.geo.Hex.neighbours(w, harbor.at())) {
            Sector s = w.sector(nb);
            if (s.owner() == c.id() && cfg.sectorType(s.designation()).hasFlag("dockside")) out.add(s);
        }
        return out;
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
        if (l.qty() <= 0) return CommandResult.fail(w, "quantity must be positive");
        double want = Math.min(l.qty(), room), got = 0;
        World next = w;
        StringBuilder from = new StringBuilder();
        for (Sector src : dockside(w, c, h)) {
            if (want - got <= 0) break;
            double q = Math.min(want - got, src.stock().get(ci));
            if (q <= 0) continue;
            next = next.withSector(next.sector(src.at()).withStock(next.sector(src.at()).stock().plus(ci, -q)));
            got += q;
            if (!src.at().equals(h.at())) from.append(from.isEmpty() ? "" : ", ").append(fmt(q)).append(" from the warehouse at ").append(src.at());
        }
        if (got <= 0) return CommandResult.fail(w, room <= 0 ? "the hold is full" : "no " + l.commodity() + " in the harbour or a warehouse beside it");
        next = next.withShip(ship.withStock(ship.stock().plus(ci, got)));
        double g = got;
        return new CommandResult(next, null, 0, "loaded " + fmt(g) + " " + l.commodity() + (from.isEmpty() ? "" : " (" + from + ")") + (g < l.qty() ? " (" + (room < l.qty() ? "hold full" : "all there was") + ")" : ""));
    }

    private CommandResult unload(World w, Country c, Command.Unload u) {
        Ship ship = myShip(w, c, u.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + u.ship() + " of yours");
        Sector h = w.sector(ship.at());
        if (!harborOf(w, c, h)) return CommandResult.fail(w, "ship #" + u.ship() + " is not in one of your harbours");
        if (!com.has(u.commodity())) return CommandResult.fail(w, "unknown commodity: " + u.commodity());
        int ci = com.index(u.commodity());
        org.hastingtx.empire.engine.update.Ctx ctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        if (u.qty() <= 0) return CommandResult.fail(w, "quantity must be positive");
        double want = Math.min(u.qty(), ship.stock().get(ci)), done = 0;
        World next = w;
        StringBuilder into = new StringBuilder();
        for (Sector dst0 : dockside(w, c, h)) {
            if (want - done <= 0) break;
            Sector dst = next.sector(dst0.at());
            double room = com.isPerson(ci) ? Math.max(0, ctx.maxPopulation(dst) - (dst.stock().get(com.civ) + dst.stock().get(com.uw)))
                                           : Math.max(0, ctx.capacity(dst, ci) - dst.stock().get(ci));
            double q = Math.min(want - done, room);
            if (q <= 0) continue;
            next = next.withSector(dst.withStock(dst.stock().plus(ci, q)));
            done += q;
            if (!dst.at().equals(h.at())) into.append(into.isEmpty() ? "" : ", ").append(fmt(q)).append(" into the warehouse at ").append(dst.at());
        }
        if (done <= 0) return CommandResult.fail(w, ship.stock().get(ci) <= 0 ? "no " + u.commodity() + " aboard" : "no room in the harbour or a warehouse beside it");
        next = next.withShip(ship.withStock(ship.stock().plus(ci, -done)));
        double d = done;
        return new CommandResult(next, null, 0, "unloaded " + fmt(d) + " " + u.commodity() + (into.isEmpty() ? "" : " (" + into + ")"));
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
        return new CommandResult(w.withShip(ship.withLane(lane).withDest(l.from()).withMission(null, null)), null, 0, "ship #" + l.ship() + " runs " + l.from() + " → " + l.to() + " carrying " + (cargo.isEmpty() ? "whatever it can" : String.join(", ", l.cargo())) + "; heading to " + l.from() + " to load");
    }

    private CommandResult fish(World w, Country c, Command.Fish f) {
        Ship ship = myShip(w, c, f.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + f.ship() + " of yours");
        if (f.off()) return new CommandResult(w.withShip(ship.withMission(null, null).withDest(null)), null, 0, "ship #" + f.ship() + " stops fishing and holds position");
        var cls = cfg.units().ships().shipClass(ship.cls());
        if (cls.fishingRateOr0() <= 0) return CommandResult.fail(w, "a " + cls.name() + " does not fish");
        Coord home = f.home() != null ? f.home() : harborOf(w, c, w.sector(ship.at())) ? ship.at() : null;
        if (home == null) return CommandResult.fail(w, "name a home harbour, or give the order while the boat is in one");
        if (!harborOf(w, c, w.sector(home))) return CommandResult.fail(w, home + " is not one of your harbours");
        if (org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), ship.at(), home) == null) return CommandResult.fail(w, "ship #" + f.ship() + " has no sea route to " + home);
        var fc = cfg.units().ships().fishingOrDefault();
        return new CommandResult(w.withShip(ship.withMission(Ship.FISH, home).withLane(null).withDest(null)), null, 0,
                "ship #" + f.ship() + " fishes the grounds within " + fc.radius() + " of " + home + " and lands the catch there");
    }

    /**
     * Seabed mining (issue #112). The same mission as fishing with a different quarry, so it is the
     * same checks — a hull that can do it, a home harbour, and a sea route back to it.
     */
    private CommandResult mine(World w, Country c, Command.Mine m) {
        Ship ship = myShip(w, c, m.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + m.ship() + " of yours");
        if (m.off()) return new CommandResult(w.withShip(ship.withMission(null, null).withDest(null)), null, 0, "ship #" + m.ship() + " stops mining and holds position");
        var cls = cfg.units().ships().shipClass(ship.cls());
        if (cls.miningRateOr0() <= 0) return CommandResult.fail(w, "a " + cls.name() + " cannot work the sea floor");
        Coord home = m.home() != null ? m.home() : harborOf(w, c, w.sector(ship.at())) ? ship.at() : null;
        if (home == null) return CommandResult.fail(w, "name a home harbour, or give the order while the ship is in one");
        if (!harborOf(w, c, w.sector(home))) return CommandResult.fail(w, home + " is not one of your harbours");
        if (org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), ship.at(), home) == null) return CommandResult.fail(w, "ship #" + m.ship() + " has no sea route to " + home);
        var mc = cfg.units().ships().miningOrDefault();
        return new CommandResult(w.withShip(ship.withMission(Ship.MINE, home).withLane(null).withDest(null)), null, 0,
                "ship #" + m.ship() + " works the nodule fields within " + mc.radius() + " of " + home + " and lands the ore there");
    }

    /** The longest thing anybody may say at once. A telegram is a message, not a pamphlet. */
    public static final int MAX_MESSAGE = 2000;

    /**
     * Validate and charge for a telegram (issue #140). The world comes back unchanged: what was said
     * is the server's to keep, because the update must be a pure function of the world and a state
     * hash must not move because somebody sent a letter.
     */
    private CommandResult telegram(World w, Country c, Command.Telegram t) {
        if (!cfg.agents().diplomacy()) return CommandResult.fail(w, "this game has diplomacy switched off");
        if (t.to() < 0 || t.to() >= w.countries().size()) return CommandResult.fail(w, "no such country");
        if (t.to() == c.id()) return CommandResult.fail(w, "you are already talking to yourself");
        String body = t.body() == null ? "" : t.body().strip();
        if (body.isEmpty()) return CommandResult.fail(w, "say something");
        if (body.length() > MAX_MESSAGE) return CommandResult.fail(w, "that is longer than " + MAX_MESSAGE + " characters");
        return new CommandResult(w, null, 0, "sent to " + w.country(t.to()).name());
    }

    private CommandResult announce(World w, Country c, Command.Announce a) {
        if (!cfg.agents().diplomacy()) return CommandResult.fail(w, "this game has diplomacy switched off");
        String body = a.body() == null ? "" : a.body().strip();
        if (body.isEmpty()) return CommandResult.fail(w, "say something");
        if (body.length() > MAX_MESSAGE) return CommandResult.fail(w, "that is longer than " + MAX_MESSAGE + " characters");
        return new CommandResult(w, null, 0, "announced to everyone");
    }

    private CommandResult scrap(World w, Country c, Command.Scrap s) {
        Ship ship = myShip(w, c, s.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + s.ship() + " of yours");
        Sector h = w.sector(ship.at());
        if (!harborOf(w, c, h)) return CommandResult.fail(w, "ship #" + s.ship() + " must be in one of your harbours to be scrapped");
        Stocks st = h.stock();
        for (int ci = 0; ci < com.size(); ci++) st = st.plus(ci, ship.stock().get(ci));   // the hold goes ashore (capacity applies at the update)
        // and so do the crew and the fuel in her tank (issues #65, #66): breaking a hull up does not
        // drown its people or pour its petrol into the harbour
        var ships = cfg.units().ships();
        if (ships != null && ships.crews() && ship.crew() > 0) {
            var cls = ships.shipClass(ship.cls());
            st = st.plus(ships.crewIsCivilian(cls) ? com.civ : com.mil, ship.crew());
        }
        if (ships != null && ships.fuel() && ship.fuel() > 0) st = st.plus(com.index(ships.fuelId()), ship.fuel());
        return new CommandResult(w.withSector(h.withStock(st)).withoutShip(ship.id()), null, 0, "ship #" + s.ship() + " scrapped at " + h.at() + "; her crew, cargo and fuel are ashore");
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
        // Nothing moves into a sector that cannot hold it (issues #48, #103). Mobility says how much you
        // could carry; this says how much there is any point carrying. Without it the move lands, the
        // apply step destroys everything above the cap, and the mobility was spent to deliver it to the
        // bin.
        double room = roomFor(ctx, to, ci);
        if (room < moving) moving = room;
        if (room <= 0) return CommandResult.fail(w, m.to() + " is full of " + m.commodity() + ": "
                + fmt(to.stock().get(ci)) + " at a limit of " + fmt(capFor(ctx, to, ci)));
        // Whole units (issue #77): a sector's stock is whole, so a move is whole. Half a civilian
        // never left anywhere, and a fractional move would be rounded away on arrival regardless.
        moving = Math.floor(moving);
        if (moving <= 0) return CommandResult.fail(w, srcPays
                ? "no mobility in " + m.from() + " (has " + fmt(from.mobility()) + "; the route costs " + fmt(totalUnit) + " per unit)"
                : "no mobility along the route (" + choke + " has " + fmt(w.sector(choke).mobility()) + ")");
        World next = w;
        if (srcPays) {
            next = next.withSector(from.withMobility(Math.max(0, from.mobility() - mobCharge(moving * totalUnit))));
        } else {
            for (int h = 1; h < path.size(); h++) {
                Sector t = next.sector(path.get(h));
                next = next.withSector(t.withMobility(Math.max(0, t.mobility() - mobCharge(moving * unit[h]))));
            }
        }
        Sector src = next.sector(m.from()), dst = next.sector(m.to());
        next = next.withSector(src.withStock(src.stock().plus(ci, -moving)));
        next = next.withSector(dst.withStock(dst.stock().plus(ci, moving)));
        String info = moving < m.qty() - 1e-9 ? "moved " + fmt(moving) + " of " + fmt(m.qty()) + " " + m.commodity() + " — " + (moving >= room - 1e-9 ? m.to() + " has room for no more" : srcPays ? "mobility in " + m.from() + " ran out" : "mobility along the route ran out") + "; the rest stayed in " + m.from()
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
        if (!w.inBounds(r.sector())) return CommandResult.fail(w, "out of bounds: " + r.sector());
        Sector s = w.sector(r.sector());
        var rail = cfg.infrastructure().rail();
        boolean bridge = s.terrain() == Terrain.OCEAN;
        if (bridge) {   // issue #60: a rail order on the sea next to your land is a bridge
            boolean adjacent = false;
            for (Coord nb : Hex.neighbours(w, r.sector())) { Sector n = w.sector(nb); if (n.owner() == c.id() && n.terrain().isLand()) adjacent = true; }
            if (!adjacent) return CommandResult.fail(w, r.sector() + " is sea with none of your land beside it; a bridge starts from your shore");
            if (rail.bridge() != null && c.levels().tech() < rail.bridge().techRequired() && r.targetLevel() > 0) return CommandResult.fail(w, "a bridge needs tech " + rail.bridge().techRequired() + "; you have " + fmt(c.levels().tech()));
        } else {
            if (s.owner() != c.id()) return CommandResult.fail(w, "you do not own " + r.sector());
            if (s.terrain() == Terrain.MOUNTAIN && s.railLevel() <= 1e-9 && rail.tunnel() != null && c.levels().tech() < rail.tunnel().techRequired() && r.targetLevel() > 0)
                return CommandResult.fail(w, "rail through a mountain is a tunnel; it needs tech " + rail.tunnel().techRequired() + " and you have " + fmt(c.levels().tech()));
        }
        if (c.levels().tech() < rail.techRequired()) return CommandResult.fail(w, "rail needs tech " + rail.techRequired() + "; you have " + fmt(c.levels().tech()));
        if (r.targetLevel() < 0 || r.targetLevel() > 100) return CommandResult.fail(w, "rail level is 0..100");
        Double cap = rail.maxLevelByTerrain().get(s.terrain().id());
        double target = cap != null && r.targetLevel() > cap ? cap : r.targetLevel();
        String capped = target < r.targetLevel() ? "rail ordered to " + Math.round(target) + ", the " + s.terrain().id() + " cap (" + Math.round(r.targetLevel()) + " asked)" : null;
        String crossing = null;
        if (target > 0 && s.railLevel() <= 1e-9) {
            var x = bridge ? rail.bridge() : s.terrain() == Terrain.MOUNTAIN ? rail.tunnel() : null;
            if (x != null) crossing = (bridge ? "a bridge: the first points also pay " : "a tunnel: the first points also pay ") + x.materials().entrySet().stream().map(e -> (e.getKey().equals("cash") ? "$" : "") + Math.round(e.getValue()) + (e.getKey().equals("cash") ? "" : " " + e.getKey())).collect(java.util.stream.Collectors.joining(", ")) + (bridge ? " from your adjacent sector with the most rail" : "");
        }
        String info = capped == null ? crossing : crossing == null ? capped : capped + "; " + crossing;
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

    /**
     * A standing rail run (issue #70). Validated the way a one-off train is — both ends working depots,
     * a line between them now — so a lane that could never run is refused at the counter rather than
     * failing quietly every update. The line can still be cut later; that is reported as it happens.
     */
    private CommandResult railLane(World w, Country c, Command.RailLane r) {
        List<org.hastingtx.empire.engine.model.RailLane> lanes = new ArrayList<>(w.railLanes());
        if (r.clear()) {
            boolean removed = lanes.removeIf(l -> l.sameRoute(c.id(), r.from(), r.to()));
            return new CommandResult(w.withRailLanes(lanes), null, 0,
                    removed ? "rail lane " + r.from() + " → " + r.to() + " cancelled" : "no rail lane " + r.from() + " → " + r.to() + " was running");
        }
        if (owned(w, c, r.from()) == null) return CommandResult.fail(w, "you do not own " + r.from());
        if (owned(w, c, r.to()) == null) return CommandResult.fail(w, "you do not own " + r.to());
        if (r.from().equals(r.to())) return CommandResult.fail(w, "a lane needs two different depots");
        org.hastingtx.empire.engine.update.Ctx ctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        if (!ctx.isDepot(w.index(r.from()))) return CommandResult.fail(w, r.from() + " is not a working depot (needs the depot designation at 60%+ and rail of at least " + fmt(cfg.infrastructure().rail().minLevelToCarry()) + ")");
        if (!ctx.isDepot(w.index(r.to()))) return CommandResult.fail(w, r.to() + " is not a working depot");
        if (ctx.railPath(r.from(), r.to(), c.id()) == null) return CommandResult.fail(w, "no rail line from " + r.from() + " to " + r.to());
        List<Integer> cargo = new ArrayList<>();
        if (r.cargo() != null) for (String k : r.cargo()) {
            if (!com.has(k)) return CommandResult.fail(w, "unknown commodity: " + k);
            cargo.add(com.index(k));
        }
        lanes.removeIf(l -> l.sameRoute(c.id(), r.from(), r.to()));
        lanes.add(new org.hastingtx.empire.engine.model.RailLane(c.id(), r.from(), r.to(), cargo));
        String what = cargo.isEmpty()
                ? "keeping " + r.to() + " topped up to its thresholds"
                : "carrying " + String.join(", ", r.cargo());
        return new CommandResult(w.withRailLanes(lanes), null, 0, "rail lane " + r.from() + " → " + r.to() + ", " + what + ", every update");
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
        // and no more than the new sector can hold (issue #103): the update would truncate the rest
        org.hastingtx.empire.engine.update.Ctx rctx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        double placeFor = Math.floor(rctx.maxPopulation(to));
        if (e.civs() > placeFor) return CommandResult.fail(w, e.to() + " holds " + fmt(placeFor) + " people; send no more than that");
        // GUESS: original charged the source sector's mobility for the walk. Cost = civs × cost into target.
        org.hastingtx.empire.engine.update.Ctx ectx = new org.hastingtx.empire.engine.update.Ctx(w, cfg, com, 0);
        double mobCost = mobCharge(e.civs() * ectx.weightLeaving(com.civ, from) * moveCostInto(to));
        if (from.mobility() < mobCost) return CommandResult.fail(w, "need " + fmt(mobCost) + " mobility in " + e.from() + ", have " + fmt(from.mobility()));
        World next = w.withSector(from.withMobility(from.mobility() - mobCost).withStock(from.stock().plus(com.civ, -e.civs())));
        next = next.withSector(to.withOwner(c.id()).withStock(to.stock().plus(com.civ, e.civs())));
        return new CommandResult(next, null, 0);
    }

    /**
     * How much more of {@code ci} this sector can take, mirroring what the apply step truncates
     * (issue #103): goods by their capacity, civilians and workers by the population cap they share,
     * and military by nothing at all — apply does not cap it, so neither does this.
     */
    private double roomFor(org.hastingtx.empire.engine.update.Ctx ctx, Sector to, int ci) {
        if (!com.isPerson(ci)) return Math.max(0, Math.floor(ctx.capacity(to, ci)) - to.stock().get(ci));
        if (ci == com.civ || ci == com.uw) return Math.max(0, Math.floor(ctx.maxPopulation(to)) - (to.stock().get(com.civ) + to.stock().get(com.uw)));
        return Double.POSITIVE_INFINITY;
    }

    /** The ceiling {@link #roomFor} measures against, for the message when there is no room left. */
    private double capFor(org.hastingtx.empire.engine.update.Ctx ctx, Sector to, int ci) {
        return com.isPerson(ci) ? Math.floor(ctx.maxPopulation(to)) : Math.floor(ctx.capacity(to, ci));
    }

    /**
     * What a sector is actually charged for a hand move. Mobility is a whole number (issue #77), and a
     * cost is rounded UP rather than half-up: half-up would make any move costing under half a point
     * free, and a free move repeated is unlimited free movement. Round-up costs at most one extra
     * point and never gives something away.
     */
    private static double mobCharge(double cost) { return cost <= 0 ? 0 : Math.ceil(cost - 1e-9); }

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
