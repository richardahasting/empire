package org.hastingtx.empire.server.auth;

import org.hastingtx.empire.server.EmpireProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** smtp: localhost:25 via Postfix. log: print the link (dev, tests, headless playtests). */
@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    private final EmpireProperties props;
    private final JavaMailSender sender;
    /** Last link handed out in log mode, so a headless test can pick it up. */
    private volatile String lastLink;

    public Mailer(EmpireProperties props, JavaMailSender sender) { this.props = props; this.sender = sender; }

    public void sendMagicLink(String to, String name, String rawToken, boolean firstLogin) {
        String link = props.appUrl().replaceAll("/+$", "") + "/verify?token=" + rawToken;
        lastLink = link;
        if (!"smtp".equalsIgnoreCase(props.mailMode())) {
            log.info("MAGIC LINK for {} ({}): {}", to, name, link);
            return;
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(props.fromName() + " <" + props.fromAddr() + ">");
        m.setTo(to);
        m.setSubject(firstLogin ? "Welcome to Empire — verify your email" : "Sign in to Empire");
        m.setText((firstLogin ? "Welcome, " + name + ".\n\nClick to verify your email and sign in:\n\n" : "Hello " + name + ".\n\nClick to sign in:\n\n")
                + link + "\n\nThe link is good for " + props.magicLinkTtlMinutes() + " minutes and works once.\n");
        sender.send(m);
        log.info("magic link sent to {}", to);
    }

    public String lastLink() { return lastLink; }
}
