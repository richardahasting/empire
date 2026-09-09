package org.hastingtx.empire.server.api;

import jakarta.servlet.http.HttpServletRequest;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.auth.AuthInterceptor;
import org.hastingtx.empire.server.game.GameService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final GameService games;
    public AdminController(GameService games) { this.games = games; }

    private static Account admin(HttpServletRequest req) {
        Account a = AuthInterceptor.current(req);
        if (!a.admin()) throw new SecurityException("deity only");
        return a;
    }

    public record CreateRequest(String preset, String name, List<String> countries, Long seed) {}

    @PostMapping("/games")
    public GameService.Summary create(@RequestBody CreateRequest r, HttpServletRequest req) {
        Account a = admin(req);
        long seed = r.seed() != null ? r.seed() : System.currentTimeMillis();
        GameService.Game g = games.create(r.preset() == null ? "teaching" : r.preset(), r.name() == null ? "Game" : r.name(), r.countries(), seed, a.id());
        return games.summary(g, a);
    }

    public record ScheduleRequest(String interval) {}

    /** interval: "24h", "15m", "0" for manual. Resets the countdown. */
    @PostMapping("/games/{id}/schedule")
    public GameService.Summary schedule(@PathVariable long id, @RequestBody ScheduleRequest r, HttpServletRequest req) {
        Account a = admin(req);
        games.setSchedule(id, GameService.parseInterval(r.interval()));
        return games.summary(games.get(id), a);
    }

    public record StatusRequest(String status) {}

    @PostMapping("/games/{id}/status")
    public GameService.Summary status(@PathVariable long id, @RequestBody StatusRequest r, HttpServletRequest req) {
        Account a = admin(req);
        games.setStatus(id, r.status());
        return games.summary(games.get(id), a);
    }

    /** Replace the game's config snapshot with its preset as shipped now, and reload. */
    @PostMapping("/games/{id}/config/refresh")
    public GameService.Summary refreshConfig(@PathVariable long id, HttpServletRequest req) {
        return games.refreshConfig(id, admin(req));
    }

    /** Seed fishing grounds for an existing game (issue #56). */
    @PostMapping("/games/{id}/sea-fertility")
    public GameService.Summary seaFertility(@PathVariable long id, HttpServletRequest req) {
        return games.seedSeaFertility(id, admin(req));
    }

    @PostMapping("/games/{id}/update")
    public Map<String, Object> update(@PathVariable long id, HttpServletRequest req) {
        admin(req);
        UpdateResult r = games.forceUpdate(id);
        return Map.of("updateNumber", r.next().updateNumber(), "stateHash", r.stateHash(), "events", r.events().size(), "flows", r.flows().size());
    }
}
