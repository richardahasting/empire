package org.hastingtx.empire.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties(prefix = "empire")
public record EmpireProperties(
        String appUrl,
        String fromAddr,
        String fromName,
        String adminEmails,
        String mailMode,
        int magicLinkTtlMinutes,
        int sessionTtlDays) {

    public List<String> adminEmailList() {
        if (adminEmails == null || adminEmails.isBlank()) return List.of();
        return Arrays.stream(adminEmails.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }
    public boolean isAdmin(String email) { return adminEmailList().contains(email.toLowerCase(Locale.ROOT)); }
}
