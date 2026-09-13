package org.hastingtx.empire.server.game;

import org.hastingtx.empire.server.auth.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue #161: a bot that hands its session token to the link-verifying step is told what it holds. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMPIRE_TEST_DB_PASSWORD", matches = ".+")
class SessionTokenIsNotALinkTest {
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> accounts = new ArrayList<>();

    @AfterEach
    void cleanup() { for (long id : accounts) jdbc.update("DELETE FROM account WHERE id = ?", id); }

    @Test
    void verifyingASessionTokenSaysSoInsteadOfUnknownLink() {
        AuthService.Session s = auth.createAgentSession("Wolfy");
        accounts.add(s.account().id());

        // the token is a live session as minted: nothing to verify
        assertThat(auth.authenticate(s.token())).isPresent();

        assertThatThrownBy(() -> auth.verify(s.token()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(AuthService.SESSION_NOT_A_LINK)
                .hasMessageContaining("Bearer");

        // and the mistake did not burn it
        assertThat(auth.authenticate(s.token())).as("still signed in after the wrong step").isPresent();
        assertThatThrownBy(() -> auth.verify("not-a-token-at-all")).hasMessage("unknown or expired link");
    }
}
