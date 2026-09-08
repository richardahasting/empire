package org.hastingtx.empire.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/** Bearer session token -> request attribute "account". Public paths are listed in WebConfig. */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String ATTR = "account";
    private final AuthService auth;
    public AuthInterceptor(AuthService auth) { this.auth = auth; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String h = req.getHeader("Authorization");
        String raw = h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
        Optional<Account> a = auth.authenticate(raw);
        if (a.isEmpty()) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"sign in required\"}");
            return false;
        }
        req.setAttribute(ATTR, a.get());
        return true;
    }

    public static Account current(HttpServletRequest req) { return (Account) req.getAttribute(ATTR); }
}
