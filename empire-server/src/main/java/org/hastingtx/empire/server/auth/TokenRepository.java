package org.hastingtx.empire.server.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/** Tokens are random 256-bit strings; only their SHA-256 is stored. */
@Repository
public class TokenRepository {
    private final JdbcClient db;
    private final SecureRandom random = new SecureRandom();
    public TokenRepository(JdbcClient db) { this.db = db; }

    public record Issued(String raw, String hash) {}

    public Issued issue(long accountId, String kind, Instant expires) {
        byte[] b = new byte[32]; random.nextBytes(b);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String hash = hash(raw);
        db.sql("INSERT INTO auth_token (token_hash, account_id, kind, expires_at) VALUES (:h, :a, :k, :e)")
                .param("h", hash).param("a", accountId).param("k", kind).param("e", expires.atOffset(java.time.ZoneOffset.UTC)).update();
        return new Issued(raw, hash);
    }

    public record Found(long accountId, String kind, Instant expiresAt, Instant usedAt) {}

    public Optional<Found> find(String raw) {
        return db.sql("SELECT account_id, kind, expires_at, used_at FROM auth_token WHERE token_hash = :h").param("h", hash(raw))
                .query((r, i) -> new Found(r.getLong("account_id"), r.getString("kind"), r.getTimestamp("expires_at").toInstant(),
                        r.getTimestamp("used_at") == null ? null : r.getTimestamp("used_at").toInstant())).optional();
    }

    /** Marks a magic token used; returns false if it was already used (race-safe). */
    public boolean consume(String raw) {
        return db.sql("UPDATE auth_token SET used_at = now() WHERE token_hash = :h AND used_at IS NULL").param("h", hash(raw)).update() == 1;
    }

    public void revoke(String raw) { db.sql("DELETE FROM auth_token WHERE token_hash = :h").param("h", hash(raw)).update(); }
    public void purgeExpired() { db.sql("DELETE FROM auth_token WHERE expires_at < now() - interval '7 days'").update(); }

    public static String hash(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
