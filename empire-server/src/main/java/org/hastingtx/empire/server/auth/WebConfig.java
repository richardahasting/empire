package org.hastingtx.empire.server.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth;
    public WebConfig(AuthInterceptor auth) { this.auth = auth; }

    @Override
    public void addInterceptors(InterceptorRegistry r) {
        r.addInterceptor(auth).addPathPatterns("/api/**").excludePathPatterns("/api/auth/request-link", "/api/auth/verify", "/api/health");
    }

    /** SPA routes: anything that is not /api or a static file gets index.html. */
    @Override
    public void addViewControllers(ViewControllerRegistry r) {
        // "/help/*" is safe to forward because the guide's own HTML is served from /guide, not /help
        for (String p : new String[] {"/", "/verify", "/games", "/games/*", "/login", "/help", "/help/*", "/admin/design-system"}) r.addViewController(p).setViewName("forward:/index.html");
    }
}
