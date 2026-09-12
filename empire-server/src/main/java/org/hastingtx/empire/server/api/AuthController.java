package org.hastingtx.empire.server.api;

import jakarta.servlet.http.HttpServletRequest;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.auth.AuthInterceptor;
import org.hastingtx.empire.server.auth.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService auth;
    private final org.hastingtx.empire.server.game.GameService games;
    public AuthController(AuthService auth, org.hastingtx.empire.server.game.GameService games) { this.auth = auth; this.games = games; }

    public record LinkRequest(String email, String name) {}
    public record VerifyRequest(String token) {}
    public record Me(long id, String email, String name, boolean admin) {
        static Me of(Account a) { return new Me(a.id(), a.email(), a.name(), a.admin()); }
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    @PostMapping("/auth/request-link")
    public Map<String, String> requestLink(@RequestBody LinkRequest r) {
        auth.requestLink(r.email(), r.name());
        return Map.of("status", "sent");
    }

    /**
     * Clicking the link is what proves the email — so it is also what turns any seat this person
     * reserved into a seat they hold (issue #126). Settling the reservation here rather than in a
     * later step means there is no window in which someone is signed in but still un-seated.
     */
    @PostMapping("/auth/verify")
    public Map<String, Object> verify(@RequestBody VerifyRequest r) {
        AuthService.Session s = auth.verify(r.token());
        games.confirmReservations(s.account().id());
        return Map.of("token", s.token(), "account", Me.of(s.account()));
    }

    @GetMapping("/me")
    public Me me(HttpServletRequest req) { return Me.of(AuthInterceptor.current(req)); }

    @PostMapping("/auth/logout")
    public Map<String, String> logout(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        auth.logout(h != null && h.startsWith("Bearer ") ? h.substring(7) : null);
        return Map.of("status", "ok");
    }
}
