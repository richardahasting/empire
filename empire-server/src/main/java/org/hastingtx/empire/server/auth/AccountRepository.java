package org.hastingtx.empire.server.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Repository
public class AccountRepository {
    private final JdbcClient db;
    public AccountRepository(JdbcClient db) { this.db = db; }

    public static String normalise(String email) { return email == null ? null : email.trim().toLowerCase(Locale.ROOT); }

    public Optional<Account> byEmail(String email) {
        return db.sql("SELECT id, email, name, is_admin FROM account WHERE email = :e").param("e", normalise(email)).query(this::map).optional();
    }
    public Optional<Account> byId(long id) {
        return db.sql("SELECT id, email, name, is_admin FROM account WHERE id = :id").param("id", id).query(this::map).optional();
    }
    public Account create(String email, String name, boolean admin) {
        long id = db.sql("INSERT INTO account (email, name, is_admin) VALUES (:e, :n, :a) RETURNING id")
                .param("e", normalise(email)).param("n", name.trim()).param("a", admin).query(Long.class).single();
        return new Account(id, normalise(email), name.trim(), admin);
    }
    public void touchLogin(long id) { db.sql("UPDATE account SET last_login = :t WHERE id = :id").param("t", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)).param("id", id).update(); }
    public long count() { return db.sql("SELECT count(*) FROM account").query(Long.class).single(); }

    private Account map(java.sql.ResultSet r, int i) throws java.sql.SQLException {
        return new Account(r.getLong("id"), r.getString("email"), r.getString("name"), r.getBoolean("is_admin"));
    }
}
