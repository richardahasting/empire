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
    public record CreateRequest(String preset, String name, Integer countries, Long seed,
                                Integer width, Integer height, Double water,
                                Integer islandSize, Integer spike, Integer minCapitalDistance,
                                Boolean wrapX, Boolean wrapY, Map<String, Double> landMix) {}

    @PostMapping("/games")
    public GameService.Summary create(@RequestBody CreateRequest r, HttpServletRequest req) {
        Account a = admin(req);
        long seed = r.seed() != null ? r.seed() : System.currentTimeMillis();
        WorldOverrides w = new WorldOverrides(r.width(), r.height(), r.water(),
                r.islandSize(), r.spike(), r.minCapitalDistance(), r.wrapX(), r.wrapY(), r.landMix(), r.countries());
        int seats = r.countries() == null ? 2 : r.countries();
        GameService.Game g = games.createWithSeats(r.preset() == null ? "teaching" : r.preset(), r.name() == null ? "Game" : r.name(), seats, seed, a.id(), w);
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

    /** {@code controller} is "human" (an open seat) or "agent" (bound to a new bot account). */
    public record AddCountryRequest(String name, String controller) {}

    /**
     * Seat a new country in a running game (issue #113). For an agent the response carries a bearer
     * token, and this is the only time it is ever shown — only its hash is stored.
     */
    @PostMapping("/games/{id}/countries")
    public GameService.Seated addCountry(@PathVariable long id, @RequestBody AddCountryRequest r, HttpServletRequest req) {
        Account a = admin(req);
        return games.addCountry(id, r.name(), r.controller() == null ? "human" : r.controller(), a);
    }

    /** Ring the starting bell early, with seats still open (issue #123). */
    @PostMapping("/games/{id}/start")
    public GameService.Summary start(@PathVariable long id, HttpServletRequest req) {
        return games.start(id, admin(req));
    }

    // ------------------------------------------------------------------ POGO, the deity (issue #128)

    /** The whole map, unfogged, in absolute coordinates — the deity's own country sits at 0,0. */
    @GetMapping("/games/{id}/view")
    public org.hastingtx.empire.engine.view.CountryView deityView(@PathVariable long id, HttpServletRequest req) {
        return games.deityView(id, admin(req));
    }

    /** Change a sector by absolute coordinates. Every edit is logged; see GameService.editSector. */
    @PostMapping("/games/{id}/sectors/{x}/{y}")
    public org.hastingtx.empire.engine.model.Sector editSector(@PathVariable long id, @PathVariable int x, @PathVariable int y,
                                                               @RequestBody GameService.SectorEdit e, HttpServletRequest req) {
        return games.editSector(id, x, y, e, admin(req));
    }

    /** Change a country's national figures. Logged, for the same reason. */
    @PostMapping("/games/{id}/countries/{countryId}/edit")
    public org.hastingtx.empire.engine.model.Country editCountry(@PathVariable long id, @PathVariable int countryId,
                                                                 @RequestBody GameService.CountryEdit e, HttpServletRequest req) {
        return games.editCountry(id, countryId, e, admin(req));
    }

    /** Mint a bot seat a new token, for one that lost the token it was given once. */
    @PostMapping("/games/{id}/countries/{countryId}/token")
    public Map<String, Object> reissueToken(@PathVariable long id, @PathVariable int countryId, HttpServletRequest req) {
        return Map.of("countryId", countryId, "token", games.reissueToken(id, countryId, admin(req)));
    }

    /** Everything a deity has changed by hand in this game, newest first. */
    @GetMapping("/games/{id}/edits")
    public List<Map<String, Object>> edits(@PathVariable long id, HttpServletRequest req) {
        admin(req);
        return games.deityEdits(id);
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
