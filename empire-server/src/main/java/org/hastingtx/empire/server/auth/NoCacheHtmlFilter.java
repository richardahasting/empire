package org.hastingtx.empire.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The SPA's index.html must never be cached: it names the hashed bundle, and a cached copy keeps
 * serving yesterday's code after a deploy until someone hard-refreshes (seen 2026-09-09, issue #54).
 * Hashed assets under /assets keep their long cache; the API sets its own headers.
 */
@Component
public class NoCacheHtmlFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String p = req.getRequestURI();
        if (!p.contains("/api/") && !p.contains("/assets/")) {
            res.setHeader("Cache-Control", "no-cache, must-revalidate");
            res.setHeader("Pragma", "no-cache");
        }
        chain.doFilter(req, res);
    }
}
