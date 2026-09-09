package org.hastingtx.empire.server.api;

import jakarta.servlet.http.HttpServletRequest;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.config.CommodityCfg;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.update.Routes;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.auth.AuthInterceptor;
import org.hastingtx.empire.server.console.Console;
import org.hastingtx.empire.server.console.SectorSelector;
import org.hastingtx.empire.server.game.GameService;
import org.hastingtx.empire.server.persistence.LogRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/games")
public class GameController {
    private final GameService games;
    private final Console console;
    private final org.hastingtx.empire.server.persistence.Json json;
    public GameController(GameService games, Console console, org.hastingtx.empire.server.persistence.Json json) { this.games = games; this.console = console; this.json = json; }

    @GetMapping
    public List<GameService.Summary> list(HttpServletRequest req) {
        Account a = AuthInterceptor.current(req);
        return games.all().stream().map(g -> games.summary(g, a)).toList();
    }

    @GetMapping("/{id}")
    public GameService.Summary one(@PathVariable long id, HttpServletRequest req) { return games.summary(games.get(id), AuthInterceptor.current(req)); }

    public record JoinRequest(int countryId) {}

    @PostMapping("/{id}/join")
    public GameService.Summary join(@PathVariable long id, @RequestBody JoinRequest r, HttpServletRequest req) {
        return games.join(id, AuthInterceptor.current(req), r.countryId());
    }

    @GetMapping("/{id}/view")
    public CountryView view(@PathVariable long id, HttpServletRequest req) { return games.view(id, AuthInterceptor.current(req)); }

    /** The rulebook the UI needs: sector types and commodities. Public knowledge. */
    public record Rules(List<SectorTypeCfg> sectorTypes, List<CommodityCfg> commodities, int etusPerUpdate, Map<String, Integer> btuCosts,
                        org.hastingtx.empire.engine.config.InfrastructureCfg.RoadCfg road, double defaultCapacity,
                        org.hastingtx.empire.engine.config.InfrastructureCfg.RailCfg rail, double productionMinEfficiency,
                        Map<String, Double> massThresholdMultiplierByType, org.hastingtx.empire.engine.config.UnitsCfg.ShipsCfg ships) {}

    @GetMapping("/{id}/rules")
    public Rules rules(@PathVariable long id) {
        var cfg = games.get(id).cfg;
        Double minEff = cfg.economy().efficiency().productionMinEfficiency();
        return new Rules(cfg.economy().sectorTypes(), cfg.commodities(), cfg.etus(), cfg.economy().btu().costByCommand(), cfg.infrastructure().road(), cfg.economy().defaultCapacity(),
                cfg.infrastructure().rail(), minEff == null ? 0 : minEff, cfg.distribution().massThresholdMultiplierByType() == null ? Map.of() : cfg.distribution().massThresholdMultiplierByType(), cfg.units().ships());
    }

    /**
     * One JSON shape for every verb; absolute coordinates. {@code scope}, when set, is a
     * {@link SectorSelector} (relative coordinates) that replaces x,y with many sectors for the
     * per-sector standing orders: designate, threshold, distribute, build_road, build_rail.
     */
    public record CommandRequest(String verb, Integer x, Integer y, Integer x2, Integer y2, String type, String commodity, Double amount, Boolean clear, String scope, String direction,
                                 Long ship, List<String> cargo, String name) {
        boolean isMass() { return scope != null && !scope.isBlank(); }
        Command toCommand() { return toCommand(x == null || y == null ? null : new Coord(x, y)); }
        /** The command for one sector; {@code at} stands in for x,y. */
        Command toCommand(Coord at) {
            return switch (verb == null ? "" : verb) {
                case "break_sanctuary" -> new Command.BreakSanctuary();
                case "designate" -> new Command.Designate(need(at), type);
                case "threshold" -> new Command.Threshold(need(at), commodity, Boolean.TRUE.equals(clear) ? -1 : amount == null ? 0 : amount);
                case "distribute" -> new Command.Distribute(need(at), Boolean.TRUE.equals(clear) || x2 == null ? null : at(x2, y2));
                case "deliver" -> {
                    boolean off = Boolean.TRUE.equals(clear) || direction == null || direction.isBlank() || direction.equalsIgnoreCase("none");
                    int d = off ? -1 : Hex.parseDir(direction);
                    if (!off && d < 0) throw new IllegalArgumentException("direction is e, ne, nw, w, sw or se");
                    yield new Command.Deliver(need(at), commodity, off ? null : d, amount == null ? 0 : amount);
                }
                case "move" -> new Command.Move(need(at), at(x2, y2), commodity, amount == null ? 0 : amount);
                case "explore" -> new Command.Explore(need(at), at(x2, y2), amount == null ? 0 : amount);
                case "build_road" -> new Command.BuildRoad(need(at), amount == null ? 0 : amount);
                case "build_rail" -> new Command.BuildRail(need(at), amount == null ? 0 : amount);
                case "rail_ship" -> new Command.RailShip(need(at), at(x2, y2), commodity, amount == null ? 0 : amount);
                case "build_ship" -> new Command.BuildShip(need(at), type, name);
                case "sail" -> new Command.Sail(needShip(), Boolean.TRUE.equals(clear) || x2 == null ? null : at(x2, y2));
                case "load" -> new Command.Load(needShip(), commodity, amount == null ? 0 : amount);
                case "unload" -> new Command.Unload(needShip(), commodity, amount == null ? 0 : amount);
                case "lane" -> new Command.Lane(needShip(), Boolean.TRUE.equals(clear) || x == null ? null : at(x, y), x2 == null ? null : at(x2, y2), cargo == null ? List.of() : cargo);
                case "scrap" -> new Command.Scrap(needShip());
                default -> throw new IllegalArgumentException("unknown verb: " + verb);
            };
        }
        private long needShip() { if (ship == null) throw new IllegalArgumentException("ship id required"); return ship; }
        private static Coord need(Coord c) {
            if (c == null) throw new IllegalArgumentException("coordinates required");
            return c;
        }
        private static Coord at(Integer x, Integer y) { return need(x == null || y == null ? null : new Coord(x, y)); }
    }

    /** Estimate in relative coordinates (capital = 0,0), so the client never sees the absolute frame. */
    public record EstimateOut(boolean ok, String error, List<Coord> path, List<Double> hopCosts, double totalMobility, int reach,
                              double arrivesQty, double heldQty, Coord holdsAt, double available, double sourceMobility) {}

    @GetMapping("/{id}/estimate")
    public EstimateOut estimate(@PathVariable long id, @RequestParam String verb, @RequestParam int x, @RequestParam int y, @RequestParam int x2, @RequestParam int y2,
                                @RequestParam(required = false) String commodity, @RequestParam(defaultValue = "0") double amount, @RequestParam(required = false) Long ship, HttpServletRequest req) {
        GameService.Game g = games.get(id);
        int country = games.myCountry(id, AuthInterceptor.current(req));
        Coord from = new Coord(x, y), to = new Coord(x2, y2);
        Routes.Estimate e = switch (verb) {
            case "move" -> Routes.move(g.world, g.cfg, country, from, to, g.com.index(commodity), amount);
            case "explore" -> Routes.explore(g.world, g.cfg, country, from, to, amount);
            case "rail" -> Routes.rail(g.world, g.cfg, country, from, to, g.com.index(commodity), amount);
            case "sail" -> Routes.sail(g.world, g.cfg, country, ship == null ? -1 : ship, to);
            default -> throw new IllegalArgumentException("verb must be move, explore, rail or sail");
        };
        Coord cap = g.world.country(country).capital();
        List<Coord> rel = e.path().stream().map(c -> CountryView.relative(g.world, cap, c)).toList();
        return new EstimateOut(e.ok(), GameService.relativise(g.world, cap, e.error()), rel, e.hopCosts(), e.totalMobility(), e.reach(), e.arrivesQty(), e.heldQty(),
                e.holdsAt() == null ? null : CountryView.relative(g.world, cap, e.holdsAt()), e.available(), e.sourceMobility());
    }

    @PostMapping("/{id}/command")
    public GameService.Outcome command(@PathVariable long id, @RequestBody CommandRequest r, HttpServletRequest req) {
        Account a = AuthInterceptor.current(req);
        if (!r.isMass()) return games.command(id, a, r.toCommand(), "panel");
        switch (r.verb() == null ? "" : r.verb()) {
            case "designate", "threshold", "distribute", "deliver", "build_road", "build_rail" -> { }
            default -> throw new IllegalArgumentException(r.verb() + " applies to one sector at a time");
        }
        CountryView v = games.view(id, a);
        var cfg = games.get(id).cfg;
        boolean scaled = "threshold".equals(r.verb()) && SectorSelector.isMixed(r.scope()) && !Boolean.TRUE.equals(r.clear());
        List<Command> cmds = SectorSelector.expand(v, cfg, r.scope()).stream()
                .map(at -> scaled ? new Command.Threshold(at, r.commodity(), SectorSelector.massThreshold(v, cfg, at, r.commodity(), r.amount() == null ? 0 : r.amount())) : r.toCommand(at)).toList();
        return games.commandAll(id, a, cmds, "panel", scaled ? SectorSelector.massThresholdNote(cfg) : null);
    }

    public record ConsoleRequest(String line) {}

    @PostMapping("/{id}/console")
    public Console.Reply console(@PathVariable long id, @RequestBody ConsoleRequest r, HttpServletRequest req) {
        return console.run(id, AuthInterceptor.current(req), r.line() == null ? "" : r.line());
    }

    @GetMapping("/{id}/projection")
    public org.hastingtx.empire.engine.update.Projection.Result projection(@PathVariable long id, HttpServletRequest req) {
        return games.projection(id, AuthInterceptor.current(req));
    }

    public record FlowOut(String kind, String commodity, double qtyPlanned, double qtyMoved, List<Coord> path, int hopsDelivered, boolean completed, String holdReason) {}
    /** {@code notes}: what happened in each of your sectors, keyed by relative "x,y", in step order (issue #49). */
    public record LastUpdate(long updateNumber, long millis, List<Map<String, Object>> events, List<FlowOut> flows, Map<String, List<String>> notes) {}

    /** Only this country's own flows and events: what other countries moved is not yours to see. */
    @GetMapping("/{id}/last-update")
    @SuppressWarnings("unchecked")
    public LastUpdate lastUpdate(@PathVariable long id, HttpServletRequest req) {
        GameService.Game g = games.get(id);
        int country = games.myCountry(id, AuthInterceptor.current(req));
        LogRepository.UpdateEntry e = games.lastUpdate(id);
        if (e == null) return new LastUpdate(0, 0, List.of(), List.of(), Map.of());
        List<Map<String, Object>> events = json.read(e.eventsJson(), List.class);
        List<Map<String, Object>> flows = json.read(e.flowsJson(), List.class);
        List<Map<String, Object>> myEvents = events.stream().filter(ev -> ((Number) ev.getOrDefault("country", -1)).intValue() == country || ((Number) ev.getOrDefault("country", -1)).intValue() == -1).toList();
        List<FlowOut> myFlows = new java.util.ArrayList<>();
        for (Map<String, Object> f : flows) {
            if (((Number) f.get("owner")).intValue() != country) continue;
            List<Map<String, Object>> path = (List<Map<String, Object>>) f.get("path");
            List<Coord> p = path.stream().map(c -> new Coord(((Number) c.get("x")).intValue(), ((Number) c.get("y")).intValue())).toList();
            myFlows.add(new FlowOut((String) f.get("kind"), g.com.id(((Number) f.get("commodity")).intValue()), ((Number) f.get("qtyPlanned")).doubleValue(),
                    ((Number) f.get("qtyMoved")).doubleValue(), p, ((Number) f.get("hopsDelivered")).intValue(), (Boolean) f.get("completed"), (String) f.get("holdReason")));
        }
        Map<String, List<String>> notes = new java.util.LinkedHashMap<>();
        Coord cap = g.world.country(country).capital();
        if (e.notesJson() != null) {
            Map<String, List<String>> all = json.read(e.notesJson(), Map.class);
            for (var n : all.entrySet()) {
                String[] xy = n.getKey().split(",");
                Coord at = new Coord(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
                if (!g.world.inBounds(at) || g.world.sector(at).owner() != country) continue;   // only your own sectors' stories
                Coord rel = CountryView.relative(g.world, cap, at);
                notes.put(rel.x() + "," + rel.y(), n.getValue().stream().map(line -> GameService.relativise(g.world, cap, line)).toList());
            }
        }
        return new LastUpdate(e.updateNumber(), e.millis(), myEvents, myFlows, notes);
    }
}
