package org.hastingtx.empire.server.api;

import jakarta.servlet.http.HttpServletRequest;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.config.CommodityCfg;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.update.Routes;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.auth.AuthInterceptor;
import org.hastingtx.empire.server.console.Console;
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
                        org.hastingtx.empire.engine.config.InfrastructureCfg.RailCfg rail, double productionMinEfficiency) {}

    @GetMapping("/{id}/rules")
    public Rules rules(@PathVariable long id) {
        var cfg = games.get(id).cfg;
        Double minEff = cfg.economy().efficiency().productionMinEfficiency();
        return new Rules(cfg.economy().sectorTypes(), cfg.commodities(), cfg.etus(), cfg.economy().btu().costByCommand(), cfg.infrastructure().road(), cfg.economy().defaultCapacity(),
                cfg.infrastructure().rail(), minEff == null ? 0 : minEff);
    }

    /** One JSON shape for every verb; absolute coordinates. */
    public record CommandRequest(String verb, Integer x, Integer y, Integer x2, Integer y2, String type, String commodity, Double amount, Boolean clear) {
        Command toCommand() {
            return switch (verb == null ? "" : verb) {
                case "break_sanctuary" -> new Command.BreakSanctuary();
                case "designate" -> new Command.Designate(at(x, y), type);
                case "threshold" -> new Command.Threshold(at(x, y), commodity, Boolean.TRUE.equals(clear) ? -1 : amount == null ? 0 : amount);
                case "distribute" -> new Command.Distribute(at(x, y), Boolean.TRUE.equals(clear) || x2 == null ? null : at(x2, y2));
                case "move" -> new Command.Move(at(x, y), at(x2, y2), commodity, amount == null ? 0 : amount);
                case "explore" -> new Command.Explore(at(x, y), at(x2, y2), amount == null ? 0 : amount);
                case "build_road" -> new Command.BuildRoad(at(x, y), amount == null ? 0 : amount);
                case "build_rail" -> new Command.BuildRail(at(x, y), amount == null ? 0 : amount);
                case "rail_ship" -> new Command.RailShip(at(x, y), at(x2, y2), commodity, amount == null ? 0 : amount);
                default -> throw new IllegalArgumentException("unknown verb: " + verb);
            };
        }
        private static Coord at(Integer x, Integer y) {
            if (x == null || y == null) throw new IllegalArgumentException("coordinates required");
            return new Coord(x, y);
        }
    }

    /** Estimate in relative coordinates (capital = 0,0), so the client never sees the absolute frame. */
    public record EstimateOut(boolean ok, String error, List<Coord> path, List<Double> hopCosts, double totalMobility, int reach,
                              double arrivesQty, double heldQty, Coord holdsAt, double available, double sourceMobility) {}

    @GetMapping("/{id}/estimate")
    public EstimateOut estimate(@PathVariable long id, @RequestParam String verb, @RequestParam int x, @RequestParam int y, @RequestParam int x2, @RequestParam int y2,
                                @RequestParam(required = false) String commodity, @RequestParam(defaultValue = "0") double amount, HttpServletRequest req) {
        GameService.Game g = games.get(id);
        int country = games.myCountry(id, AuthInterceptor.current(req));
        Coord from = new Coord(x, y), to = new Coord(x2, y2);
        Routes.Estimate e = switch (verb) {
            case "move" -> Routes.move(g.world, g.cfg, country, from, to, g.com.index(commodity), amount);
            case "explore" -> Routes.explore(g.world, g.cfg, country, from, to, amount);
            case "rail" -> Routes.rail(g.world, g.cfg, country, from, to, g.com.index(commodity), amount);
            default -> throw new IllegalArgumentException("verb must be move, explore or rail");
        };
        Coord cap = g.world.country(country).capital();
        List<Coord> rel = e.path().stream().map(c -> CountryView.relative(g.world, cap, c)).toList();
        return new EstimateOut(e.ok(), GameService.relativise(g.world, cap, e.error()), rel, e.hopCosts(), e.totalMobility(), e.reach(), e.arrivesQty(), e.heldQty(),
                e.holdsAt() == null ? null : CountryView.relative(g.world, cap, e.holdsAt()), e.available(), e.sourceMobility());
    }

    @PostMapping("/{id}/command")
    public GameService.Outcome command(@PathVariable long id, @RequestBody CommandRequest r, HttpServletRequest req) {
        return games.command(id, AuthInterceptor.current(req), r.toCommand(), "panel");
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
    public record LastUpdate(long updateNumber, long millis, List<Map<String, Object>> events, List<FlowOut> flows) {}

    /** Only this country's own flows and events: what other countries moved is not yours to see. */
    @GetMapping("/{id}/last-update")
    @SuppressWarnings("unchecked")
    public LastUpdate lastUpdate(@PathVariable long id, HttpServletRequest req) {
        GameService.Game g = games.get(id);
        int country = games.myCountry(id, AuthInterceptor.current(req));
        LogRepository.UpdateEntry e = games.lastUpdate(id);
        if (e == null) return new LastUpdate(0, 0, List.of(), List.of());
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
        return new LastUpdate(e.updateNumber(), e.millis(), myEvents, myFlows);
    }
}
