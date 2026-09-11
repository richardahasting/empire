package org.hastingtx.empire.server.api;

import jakarta.servlet.http.HttpServletRequest;
import org.hastingtx.empire.engine.update.UpdateResult;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.auth.AuthInterceptor;
import org.hastingtx.empire.server.game.GameService;
import org.hastingtx.empire.server.game.WorldOverrides;
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

    /**
     * Every world field is optional and a null keeps the preset's value (issues #81, #105).
     * {@code width}/{@code height} are in sectors, {@code water} is the percent of the map that is sea,
     * and {@code landMix} is per-terrain weights that need not sum to anything in particular.
     */
    public record CreateRequest(String preset, String name, List<String> countries, Long seed,
                                Integer width, Integer height, Double water,
                                Integer islandSize, Integer spike, Integer minCapitalDistance,
                                Boolean wrapX, Boolean wrapY, Map<String, Double> landMix) {}

    @PostMapping("/games")
    public GameService.Summary create(@RequestBody CreateRequest r, HttpServletRequest req) {
        Account a = admin(req);
        long seed = r.seed() != null ? r.seed() : System.currentTimeMillis();
        WorldOverrides w = new WorldOverrides(r.width(), r.height(), r.water(),
                r.islandSize(), r.spike(), r.minCapitalDistance(), r.wrapX(), r.wrapY(), r.landMix());
        GameService.Game g = games.create(r.preset() == null ? "teaching" : r.preset(), r.name() == null ? "Game" : r.name(), r.countries(), seed, a.id(), w);
        return games.summary(g, a);
    }

    /** What one preset's world looks like before any override, so the create form can show real defaults (issue #105). */
    public record PresetWorld(String preset, String name, int width, int height, boolean wrapX, boolean wrapY,
                              double water, int islandSize, int spike, int minCapitalDistance,
                              Map<String, Double> landMix, int maxCountries) {}

    @GetMapping("/presets")
    public List<PresetWorld> presets(HttpServletRequest req) {
        admin(req);
        return games.presetWorlds();
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

    /** Delete a game and everything it owns. Irreversible (issue #109). */
    @DeleteMapping("/games/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest req) {
        Account a = admin(req);
        String name = games.get(id).name;
        games.delete(id, a);
        return Map.of("deleted", id, "name", name);
    }

    @PostMapping("/games/{id}/update")
    public Map<String, Object> update(@PathVariable long id, HttpServletRequest req) {
        admin(req);
        UpdateResult r = games.forceUpdate(id);
        return Map.of("updateNumber", r.next().updateNumber(), "stateHash", r.stateHash(), "events", r.events().size(), "flows", r.flows().size());
    }
}
