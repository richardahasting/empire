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
    public GameController(GameService games, Console console) { this.games = games; this.console = console; }

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
    public record Rules(List<SectorTypeCfg> sectorTypes, List<CommodityCfg> commodities, int etusPerUpdate, Map<String, Integer> btuCosts) {}

    @GetMapping("/{id}/rules")
    public Rules rules(@PathVariable long id) {
        var cfg = games.get(id).cfg;
        return new Rules(cfg.economy().sectorTypes(), cfg.commodities(), cfg.etus(), cfg.economy().btu().costByCommand());
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
            default -> throw new IllegalArgumentException("verb must be move or explore");
        };
        Coord cap = g.world.country(country).capital();
        List<Coord> rel = e.path().stream().map(c -> CountryView.relative(g.world, cap, c)).toList();
        return new EstimateOut(e.ok(), e.error(), rel, e.hopCosts(), e.totalMobility(), e.reach(), e.arrivesQty(), e.heldQty(),
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

    @GetMapping("/{id}/last-update")
    public Map<String, Object> lastUpdate(@PathVariable long id, HttpServletRequest req) {
        games.myCountry(id, AuthInterceptor.current(req));
        LogRepository.UpdateEntry e = games.lastUpdate(id);
        if (e == null) return Map.of("updateNumber", 0);
        return Map.of("updateNumber", e.updateNumber(), "stateHash", e.stateHash(), "millis", e.millis(), "events", RawJson.of(e.eventsJson()), "flows", RawJson.of(e.flowsJson()));
    }
}
