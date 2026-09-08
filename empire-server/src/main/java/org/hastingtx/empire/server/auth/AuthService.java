package org.hastingtx.empire.server.auth;

import org.hastingtx.empire.server.EmpireProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AuthService {
    private final AccountRepository accounts;
    private final TokenRepository tokens;
    private final Mailer mailer;
    private final EmpireProperties props;

    public AuthService(AccountRepository accounts, TokenRepository tokens, Mailer mailer, EmpireProperties props) {
        this.accounts = accounts; this.tokens = tokens; this.mailer = mailer; this.props = props;
    }

    /** Step 1: request a link. Creates the account on first contact. Always succeeds from the caller's view. */
    @Transactional
    public void requestLink(String email, String name) {
        String e = AccountRepository.normalise(email);
        if (e == null || !e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new IllegalArgumentException("that does not look like an email address");
        Optional<Account> existing = accounts.byEmail(e);
        boolean first = existing.isEmpty();
        if (first && (name == null || name.isBlank())) throw new IllegalArgumentException("a name is needed the first time");
        Account a = existing.orElseGet(() -> accounts.create(e, name, props.isAdmin(e)));
        TokenRepository.Issued t = tokens.issue(a.id(), "magic", Instant.now().plus(Duration.ofMinutes(props.magicLinkTtlMinutes())));
        mailer.sendMagicLink(e, a.name(), t.raw(), first);
    }

    public record Session(String token, Account account) {}

    /** Step 2: the link is clicked. Burns the magic token, issues a long-lived session token. */
    @Transactional
    public Session verify(String rawMagic) {
        TokenRepository.Found f = tokens.find(rawMagic).orElseThrow(() -> new IllegalArgumentException("unknown or expired link"));
        if (!f.kind().equals("magic") || f.expiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("unknown or expired link");
        if (!tokens.consume(rawMagic)) throw new IllegalArgumentException("that link was already used");
        Account a = accounts.byId(f.accountId()).orElseThrow();
        accounts.touchLogin(a.id());
        TokenRepository.Issued s = tokens.issue(a.id(), "session", Instant.now().plus(Duration.ofDays(props.sessionTtlDays())));
        return new Session(s.raw(), a);
    }

    public Optional<Account> authenticate(String rawSession) {
        if (rawSession == null || rawSession.isBlank()) return Optional.empty();
        return tokens.find(rawSession)
                .filter(f -> f.kind().equals("session") && f.expiresAt().isAfter(Instant.now()))
                .flatMap(f -> accounts.byId(f.accountId()));
    }

    public void logout(String rawSession) { if (rawSession != null) tokens.revoke(rawSession); }
}
