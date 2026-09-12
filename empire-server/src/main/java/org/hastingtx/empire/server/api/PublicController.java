package org.hastingtx.empire.server.api;

import org.hastingtx.empire.server.game.GameService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * What a visitor may see and do before they have an account (issue #126).
 *
 * <p>Only two things: which games have a seat going, and taking one. Everything here is already
 * public to anyone inside a game — country names appear on every player's map — and nothing that is
 * not: no email addresses, no accounts, no map, no stocks.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final GameService games;
    public PublicController(GameService games) { this.games = games; }

    /** A game with room in it, as a stranger is allowed to see it. */
    public record OpenGame(long id, String name, String preset, String status, int width, int height,
                           long updateNumber, int openSeats, List<OpenSeat> seats) {}

    /** {@code state} is open | taken | reserved — reserved being somebody mid-claim. */
    public record OpenSeat(int countryId, String name, String state) {}

    @GetMapping("/games")
    public List<OpenGame> games() {
        List<OpenGame> out = new ArrayList<>();
        java.time.Instant now = java.time.Instant.now();
        for (GameService.Game g : games.all()) {
            if (!"setup".equals(g.status) && !"running".equals(g.status)) continue;
            List<OpenSeat> seats = new ArrayList<>();
            for (var s : games.playerSeats(g.id))
                seats.add(new OpenSeat(s.countryId(), s.name(), s.held() ? "taken" : s.reserved(now) ? "reserved" : "open"));
            int open = (int) seats.stream().filter(s -> s.state().equals("open")).count();
            if (open == 0) continue;
            out.add(new OpenGame(g.id, g.name, g.preset, g.status, g.world.width(), g.world.height(), g.world.updateNumber(), open, seats));
        }
        return out;
    }

    public record ClaimRequest(int countryId, String name, String email, String yourName) {}

    /**
     * Take a seat and name it, before having an account. The seat is held, not taken, until the
     * magic link that goes out is clicked — see {@link GameService#claim}.
     */
    @PostMapping("/games/{id}/claim")
    public GameService.Claimed claim(@PathVariable long id, @RequestBody ClaimRequest r) {
        return games.claim(id, r.countryId(), r.name(), r.email(), r.yourName());
    }
}
