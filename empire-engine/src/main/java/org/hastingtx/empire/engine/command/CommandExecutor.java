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
            case Command.DeclareWar d -> declareWar(w, c, d);
            case Command.OfferPeace o -> offerPeace(w, c, o);
            case Command.Telegram t -> telegram(w, c, t);
            case Command.Announce a -> announce(w, c, a);
            case Command.Fish f -> fish(w, c, f);
            case Command.Mine m -> mine(w, c, m);
            case Command.Supply sp -> supply(w, c, sp);
            case Command.Fire fi -> Engagement.fire(cfg, com, w, c, fi);
            case Command.Mission mi -> mission(w, c, mi);
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

    private CommandResult designate(World w, Country c, Command.Designate d0) {
        Sector s = owned(w, c, d0.sector());
        if (s == null) return CommandResult.fail(w, "you do not own " + d0.sector());
        // id, alias or glyph (issue #157): "manufacturer" is light_manufacturing, and the ack says so
        SectorTypeCfg t = cfg.resolveSectorType(d0.type());
        if (t == null) return CommandResult.fail(w, "unknown designation: " + d0.type());
        Command.Designate d = t.id().equals(d0.type()) ? d0 : new Command.Designate(d0.sector(), t.id());
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
        // the ack leads with what was done; whatever auto-wiring did is a secondary note (issue #149)
        StringBuilder info = new StringBuilder("now ").append(d.type()).append(" (").append(t.glyph()).append(")");
        if (d != d0) info.append(" — '").append(d0.type()).append("' means ").append(d.type());
        if (!"wilderness".equals(s.designation())) info.append(", was ").append(s.designation());
        if (Math.abs(eff - s.efficiency()) > 1e-9) info.append("; efficiency ").append(Math.round(s.efficiency())).append(" → ").append(Math.round(eff));
        // poor ground (issue #157): the gate scales output, so 8 minerals is 8% of a real mine — say so now, not after three updates of nothing
        if (t.resourceGate() != null && s.resource(t.resourceGate()) < cfg.economy().poorGroundBelowOrDefault())
            info.append(" — WARNING: ").append(t.resourceGate()).append(' ').append(s.resource(t.resourceGate())).append(" is poor ground for ").append(d.type().replace('_', ' '))
                .append("; it will make about ").append(s.resource(t.resourceGate())).append("% of what a hex at 100 would");
        if (wired != null) info.append(" — auto-wired: ").append(wired);
        return new CommandResult(next, null, 0, info.toString());
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
        // A standing order toward somewhere it can never deliver used to be "noted" and then kept —
        // a dead pipe that looked like a success (playtest game 82, issue #147). It is refused now:
        // nothing is written, and the reason names the hex so the player can look at it.
        if (!t.terrain().isLand()) return CommandResult.fail(w, to + " is sea; nothing can be delivered there (deliver … check to look before you order)");
        if (t.owner() != c.id()) return CommandResult.fail(w, "you do not own " + to + "; explore it first, or deliver … check to see what lies " + Hex.dirName(d.dir()));
        // One order per commodity per sector. Replacing one used to be silent, and probing a
        // direction quietly destroyed a working chain (issue #148). It still replaces — but says so.
        String replaced = s.deliver().has(ci) && (s.deliver().dir(ci) != d.dir() || s.deliver().threshold(ci) != d.threshold())
                ? " — REPLACES the previous order (" + d.commodity() + " above " + fmt(s.deliver().threshold(ci)) + " went " + Hex.dirName(s.deliver().dir(ci)) + ")"
                : "";
        String info = d.commodity() + " above " + fmt(d.threshold()) + " goes " + Hex.dirName(d.dir()) + " to " + to + " every update" + replaced + selfStarveWarning(s, ci, d.threshold());
        return new CommandResult(w.withSector(s.withDeliver(s.deliver().with(ci, d.dir(), d.threshold()))), null, 0, info);
    }

    /**
     * A food delivery that leaves less than the sector's own people eat in an update is a pipe that
     * starves its source (issue #158). It is allowed — a player may mean it — but it is said out loud.
     */
    private String selfStarveWarning(Sector s, int ci, double threshold) {
        if (ci != com.food) return "";
        double eats = org.hastingtx.empire.engine.update.FoodMath.eatsPerUpdate(cfg, com, s);
        if (eats <= 0) return "";
        StringBuilder sb = new StringBuilder();
        if (threshold < eats)
            sb.append(" — WARNING: the people here eat about ").append(Math.round(eats)).append(" food an update and this keeps only ").append(fmt(threshold)).append("; they will starve once the stock is gone");
        if (s.hasThreshold(ci) && s.stock().get(ci) < s.threshold(ci))
            sb.append(sb.isEmpty() ? " — WARNING: " : "; also ").append("this sector is under its own food threshold (").append(Math.round(s.stock().get(ci))).append(" of ").append(Math.round(s.threshold(ci))).append(") and is still asking its centre for more");
        return sb.toString();
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
        // an armed hull's guns and shells are her armament, not scrap (issue #68): they go aboard
        Stocks aboard = Stocks.zero(com.size());
        if (cls.armed()) for (String k : new String[] {"gun", "shell"}) {
            Double q = cls.buildOrEmpty().get(k);
            if (q != null && q > 0) aboard = aboard.plus(com.index(k), q);
        }
        Ship ship = new Ship(id, c.id(), cls.id(), b.name() == null ? "" : b.name().trim(), h.at(), sc.startEfficiency(), aboard, null, null, w.updateNumber(), "laid down", c.levels().tech(), null, null);
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
        String ended = ship.roaming() || ship.supplying() ? " (" + ship.mission() + (ship.supplying() ? " mission" : "ing mission") + " ended)" : "";
        int hexes = path.size() - 1;
        ship = ship.withDest(s.dest()).withMission(null, null);

        // A sail happens now (issue #69). The ship goes as far as its own mobility and its tank will
        // carry it, this command, and the rest waits for the update — which is what makes a warship
        // able to answer something it has just seen instead of an update later.
        if (sc.immediate()) {
            if (sc.crews() && ship.crew() < cls.crewOr0())
                return new CommandResult(w.withShip(ship), null, 0, "ship #" + s.ship() + " is short-handed and stays at the quay; it will sail when it has a crew" + ended);
            // she does not leave port until her tank is full (Richard 2026-09-14)
            if (sc.fuel() && harborOf(w, c, w.sector(ship.at())) && cls.tankOr0() - ship.fuel() >= 1)
                return new CommandResult(w.withShip(ship), null, 0, "ship #" + s.ship() + " is filling her tank (" + fmt(ship.fuel()) + " of " + fmt(cls.tankOr0()) + "); she sails for " + s.dest() + " at the update once it is full" + ended);
            double perHex = sc.fuel() ? cls.fuelPerHexOr0() : 0;
            int byFuel = perHex > 0 ? (int) Math.floor(ship.fuel() / perHex) : hexes;
            // haste is dearer than planning: a hex ordered now costs rushCost, a planned one costs 1
            double rush = sc.rushCost();
            int hops = Math.min(Math.min((int) Math.floor(ship.mobility() / rush), hexes), byFuel);
            // a hostile blockade on station stops her where she meets it (issue #68)
            var blocked = org.hastingtx.empire.engine.combat.Blockade.limit(w, cfg, ship, path, hops, w.updateNumber());
            if (blocked.by() != null) {
                hops = blocked.hops();
                String who = w.country(blocked.by().owner()).name();
                if (hops <= 0) return new CommandResult(w.withShip(ship), null, 0, "ship #" + s.ship() + " is held by " + who + "'s blockade at " + ship.at() + ended);
                Coord to = path.get(hops);
                ship = ship.withAt(to).withMobility(ship.mobility() - hops * rush);
                if (perHex > 0) ship = ship.withFuel(ship.fuel() - hops * perHex);
                return new CommandResult(w.withShip(ship), null, 0, "ship #" + s.ship() + " sails " + hops + (hops == 1 ? " hex" : " hexes") + " and is stopped by " + who + "'s blockade at " + to + ended);
            }
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
        if (cls.military()) return CommandResult.fail(w, "a " + cls.name() + " does not run cargo");
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

    /**
     * A military mission (issue #68). Only a hull with guns; every mission needs a harbour to come home
     * to for shells, fuel and repairs — the one she is in, or the nearest of yours she can reach.
     */
    private CommandResult mission(World w, Country c, Command.Mission m) {
        Ship ship = myShip(w, c, m.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + m.ship() + " of yours");
        String kind = m.kind() == null ? "" : m.kind().toLowerCase(java.util.Locale.ROOT);
        if (!Ship.MILITARY_MISSIONS.contains(kind)) return CommandResult.fail(w, "no such mission: " + m.kind());
        if (m.off()) {
            if (!kind.equals(ship.mission())) return CommandResult.fail(w, "ship #" + ship.id() + " is not on " + kind);
            return new CommandResult(w.withShip(ship.withMission(null, null).withDest(null)), null, 0, "ship #" + ship.id() + " comes off " + kind + " and holds position");
        }
        var sc = cfg.units().ships();
        var cls = sc.shipClass(ship.cls());
        if (!cls.armed()) return CommandResult.fail(w, "a " + cls.name() + " has no guns; missions are for warships");
        Coord home = harborOf(w, c, w.sector(ship.at())) ? ship.at() : nearestHarbour(w, c, ship);
        if (home == null) return CommandResult.fail(w, "ship #" + ship.id() + " has no harbour of yours to come home to");
        List<Coord> points = m.points() == null ? List.of() : m.points();
        long ward = 0;
        String what;
        switch (kind) {
            case Ship.PATROL -> {
                if (points.size() < 2) return CommandResult.fail(w, "a patrol needs at least two points to walk between");
                for (int i = 0; i < points.size(); i++) {
                    String bad = navigable(w, c, points.get(i));
                    if (bad != null) return CommandResult.fail(w, bad);
                    Coord next = points.get((i + 1) % points.size());
                    if (org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), points.get(i), next) == null) return CommandResult.fail(w, "no sea route from " + points.get(i) + " to " + next);
                }
                if (org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), ship.at(), points.get(0)) == null) return CommandResult.fail(w, "ship #" + ship.id() + " has no sea route to " + points.get(0));
                what = "patrols " + points.stream().map(Coord::toString).collect(java.util.stream.Collectors.joining(" → ")) + " and round again";
            }
            case Ship.BLOCKADE, Ship.INTERDICT -> {
                if (points.size() != 1) return CommandResult.fail(w, "a " + kind + " holds one station: give one x,y");
                String bad = navigable(w, c, points.get(0));
                if (bad != null) return CommandResult.fail(w, bad);
                if (org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), ship.at(), points.get(0)) == null) return CommandResult.fail(w, "ship #" + ship.id() + " has no sea route to " + points.get(0));
                what = kind.equals(Ship.BLOCKADE)
                        ? "holds " + points.get(0) + "; at war, an enemy ship that comes within " + sc.missionsOrDefault().blockadeRadiusOr0() + " of her is stopped there"
                        : "holds " + points.get(0) + "; at war, she shells enemy trains within her guns' reach";
            }
            case Ship.ESCORT -> {
                Ship charge = myShip(w, c, m.ward());
                if (charge == null || charge.id() == ship.id()) return CommandResult.fail(w, "escort which of your other ships?");
                ward = charge.id();
                what = "stays with ship #" + charge.id();
            }
            default -> {
                points = List.of();
                what = "searches the water within " + sc.missionsOrDefault().searchRadiusOr0() + " of " + home + ", going where you have not looked lately";
            }
        }
        return new CommandResult(w.withShip(ship.withOrders(kind, home, points, ward).withLane(null).withDest(null)), null, 0,
                "ship #" + ship.id() + " " + what + "; she comes home to " + home + " for shells, fuel and repairs. At war she fights what she finds; at peace she only watches");
    }

    /** Null if a ship may be sent to {@code at}; otherwise why not. */
    private String navigable(World w, Country c, Coord at) {
        if (at == null || !w.inBounds(at)) return "out of bounds: " + at;
        return org.hastingtx.empire.engine.update.SeaRoutes.navigable(w, cfg, w.sector(at), c.id()) ? null : at + " is not sea or one of your harbours";
    }

    /** The nearest harbour of yours she can sail to, by hexes then position. */
    private Coord nearestHarbour(World w, Country c, Ship ship) {
        Coord best = null; int bestD = Integer.MAX_VALUE;
        for (Sector s : w.ownedBy(c.id())) {
            if (!harborOf(w, c, s)) continue;
            int d = Hex.distance(w, ship.at(), s.at());
            if (d < bestD && org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), ship.at(), s.at()) != null) { best = s.at(); bestD = d; }
        }
        return best;
    }

    /**
     * The supply mission (issue #67). Needs a hull that carries something and a harbour to call home,
     * where she goes to be refitted; what she carries and where is decided at the update, from the
     * thresholds, so there is nothing else to say here.
     */
    private CommandResult supply(World w, Country c, Command.Supply m) {
        Ship ship = myShip(w, c, m.ship());
        if (ship == null) return CommandResult.fail(w, "no ship #" + m.ship() + " of yours");
        if (m.off()) return new CommandResult(w.withShip(ship.withMission(null, null).withDest(null)), null, 0, "ship #" + m.ship() + " comes off supply and holds position");
        var cls = cfg.units().ships().shipClass(ship.cls());
        if (cls.carriesOrEmpty().isEmpty()) return CommandResult.fail(w, "a " + cls.name() + " carries no cargo");
        if (cls.military()) return CommandResult.fail(w, "a " + cls.name() + " does not run cargo");
        Coord home = m.home() != null ? m.home() : harborOf(w, c, w.sector(ship.at())) ? ship.at() : null;
        if (home == null) return CommandResult.fail(w, "name a home harbour, or give the order while the ship is in one");
        if (!harborOf(w, c, w.sector(home))) return CommandResult.fail(w, home + " is not one of your harbours");
        if (org.hastingtx.empire.engine.update.SeaRoutes.path(w, cfg, c.id(), ship.at(), home) == null) return CommandResult.fail(w, "ship #" + m.ship() + " has no sea route to " + home);
        long harbours = w.sectors().stream().filter(s -> harborOf(w, c, s)).count();
        String what = cls.carriesOrEmpty().contains("all") ? "anything" : String.join(" and ", cls.carriesOrEmpty());
        return new CommandResult(w.withShip(ship.withMission(Ship.SUPPLY, home).withLane(null).withDest(null)), null, 0,
                "ship #" + m.ship() + " carries " + what + " between your harbours wherever a threshold is short, refitting at " + home
                        + (harbours < 2 ? " — you have one harbour, so there is nowhere to carry anything yet" : ""));
    }

    /**
     * Declare war (issue #137). One row per pair, so the state is mutual by construction — there is
     * no way to record a war the other side is not in.
     */
    private CommandResult declareWar(World w, Country c, Command.DeclareWar d) {
        Country them = target(w, d.on());
        if (them == null) return CommandResult.fail(w, "no such country");
        if (them.id() == c.id()) return CommandResult.fail(w, "you cannot declare war on yourself");
        if (c.inSanctuary()) return CommandResult.fail(w, "break sanctuary before you go to war");
        if (them.inSanctuary()) return CommandResult.fail(w, them.name() + " is still in sanctuary and cannot be touched");
        if (w.atWar(c.id(), them.id())) return CommandResult.fail(w, "you are already at war with " + them.name());
        return new CommandResult(withRelation(w, c.id(), them.id(), Relation.WAR, null), null, 0,
                "war declared on " + them.name());
    }

    /**
     * Offer peace, and take it if it was already offered. Both sides have to want it, so a war cannot
     * be switched off the moment it goes badly — which is what makes declaring one a decision.
     */
    private CommandResult offerPeace(World w, Country c, Command.OfferPeace o) {
        Country them = target(w, o.with());
        if (them == null) return CommandResult.fail(w, "no such country");
        if (them.id() == c.id()) return CommandResult.fail(w, "you are at peace with yourself");
        Relation r = w.relation(c.id(), them.id());
        if (r == null || !r.atWar()) return CommandResult.fail(w, "you are not at war with " + them.name());
        if (r.peaceOfferedBy() != null && r.peaceOfferedBy() == them.id())
            return new CommandResult(withRelation(w, c.id(), them.id(), Relation.PEACE, null), null, 0,
                    "peace with " + them.name() + " — they had offered, and you have accepted");
        if (r.peaceOfferedBy() != null && r.peaceOfferedBy() == c.id())
            return CommandResult.fail(w, "you have already offered " + them.name() + " peace; it is theirs to accept");
        return new CommandResult(withRelation(w, c.id(), them.id(), Relation.WAR, c.id()), null, 0,
                "peace offered to " + them.name() + "; it holds until they accept");
    }

    private Country target(World w, int id) { return id < 0 || id >= w.countries().size() ? null : w.country(id); }

    /** Replace the pair's relation, keeping the one-row-per-pair invariant. */
    private static World withRelation(World w, int x, int y, String state, Integer offeredBy) {
        List<Relation> next = new java.util.ArrayList<>();
        for (Relation r : w.relations()) if (!r.between(x, y)) next.add(r);
        next.add(Relation.of(x, y, state, w.updateNumber(), offeredBy));
        return w.withRelations(next);
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
        var road = cfg.infrastructure().road();
        Double cap = road.maxLevelByTerrain().get(s.terrain().id());
        double target = cap != null && r.targetLevel() > cap ? cap : r.targetLevel();
        if (target <= 0) return new CommandResult(w.withSector(s.withRoadTarget(0)), null, 0, s.roadTarget() > 0 ? "road order at " + r.sector() + " cancelled" : null);
        String capped = target < r.targetLevel() ? "road ordered to " + Math.round(target) + ", the " + s.terrain().id() + " cap (" + Math.round(r.targetLevel()) + " asked)" : "road ordered to " + Math.round(target) + " from " + Math.round(s.roadLevel());
        if (target <= s.roadLevel() + 1e-9) return new CommandResult(w.withSector(s.withRoadTarget(target)), null, 0, capped + " — already there");
        Double mult = road.costMultiplierByTerrain().get(s.terrain().id());
        return new CommandResult(w.withSector(s.withRoadTarget(target)), null, 0,
                capped + "; " + materialsNote(w, c, s, road.buildMaterialsPerPoint(), mult == null ? 1 : mult, road.maxPointsPerUpdate(), "paving"));
    }

    /**
     * What a standing road or rail order costs here and whether the sector can pay for a point yet
     * (issue #150). The order is accepted at once but nothing is built until a whole point's worth
     * of materials is in the sector, and a player who did not know that thought roads were broken.
     */
    private String materialsNote(World w, Country c, Sector s, java.util.Map<String, Double> perPoint, double m, double maxPoints, String doing) {
        StringBuilder cost = new StringBuilder();
        double points = maxPoints;
        StringBuilder have = new StringBuilder(), need = new StringBuilder();
        for (var e : perPoint.entrySet()) {
            double per = e.getValue() * m;
            if (per <= 0) continue;
            boolean cash = e.getKey().equals("cash");
            cost.append(cost.isEmpty() ? "" : ", ").append(cash ? "$" : "").append(fmt(per)).append(cash ? "" : " " + e.getKey());
            double avail = cash ? c.cash() : s.stock().get(com.index(e.getKey()));
            points = Math.min(points, avail / per);
            if (cash) continue;
            have.append(have.isEmpty() ? "" : ", ").append(Math.round(avail)).append(' ').append(e.getKey());
            if (avail < per) need.append(need.isEmpty() ? "" : " and ").append((long) Math.ceil(per - avail)).append(" more ").append(e.getKey());
        }
        String terrain = m == 1 ? "" : " (" + s.terrain().id() + " ×" + fmt(m) + ")";
        String head = doing + " costs " + cost + " a point here" + terrain + ", up to " + Math.round(maxPoints) + " points an update — the sector has " + have;
        if (need.length() > 0) return head + ": NEEDS " + need + " in-sector before the first point is laid (nothing happens until then)";
        if (points < 1) return head + ": not enough cash for a point";
        return head + ": enough for " + (long) Math.floor(Math.min(points, maxPoints)) + (points >= 2 ? " points" : " point") + " next update";
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
        String materials = null;
        if (target > 0 && !bridge && target > s.railLevel() + 1e-9) {
            Double mult = rail.costMultiplierByTerrain() == null ? null : rail.costMultiplierByTerrain().get(s.terrain().id());
            materials = materialsNote(w, c, s, rail.buildMaterialsPerPoint(), mult == null ? 1 : mult, rail.maxPointsPerUpdate(), "laying rail");
        }
        String info = java.util.stream.Stream.of(capped, crossing, materials).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining("; "));
        return new CommandResult(w.withSector(s.withRailTarget(target)), null, 0, info.isEmpty() ? null : info);
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
        // Food walks with the party (playtest game 82, issue #151). Civilians on ground that cannot
        // feed them by foraging were starving at the very next update; half a food a head is nothing
        // to the source and about sixteen updates to the party. Sent from the source, as much as it has.
        double wanted = e.civs() * cfg.economy().population().exploreFoodPerCivOrDefault();
        double food = Math.min(wanted, Math.max(0, from.stock().get(com.food)));
        World next = w.withSector(from.withMobility(from.mobility() - mobCost)
                .withStock(from.stock().plus(com.civ, -e.civs()).plus(com.food, -food)));
        next = next.withSector(to.withOwner(c.id()).withStock(to.stock().plus(com.civ, e.civs()).plus(com.food, food)));

        // and say whether they will be all right: the first N people forage for free, the rest eat stock
        var sub = cfg.economy().population().subsistence();
        double forage = sub == null ? 0 : sub.civsPerSector() * (sub.scaleByFertility() ? to.resource("fertility") / 100.0 : 1.0);
        StringBuilder info = new StringBuilder(fmt(e.civs()) + " civilians settle " + e.to());
        if (food > 0) info.append(" with ").append(fmt(food)).append(" food");
        if (e.civs() > forage) {
            double eating = (e.civs() - forage) * cfg.economy().population().foodPerCivPerEtu() * cfg.schedule().etusPerUpdate();
            if (food <= 0) info.append(" — WARNING: ").append(fmt(from.stock().get(com.food))).append(" food at ").append(e.from())
                    .append(", none to send, and fertility ").append(to.resource("fertility")).append(" feeds only ").append(fmt(Math.floor(forage)))
                    .append(" of them: they will starve at the next update");
            else info.append(" (fertility ").append(to.resource("fertility")).append(" feeds ").append(fmt(Math.floor(forage)))
                    .append("; the rest eat about ").append(fmt(eating)).append(" a update, so that lasts ~").append(fmt(Math.floor(food / Math.max(eating, 1e-9)))).append(" updates)");
        }
        return new CommandResult(next, null, 0, info.toString());
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
