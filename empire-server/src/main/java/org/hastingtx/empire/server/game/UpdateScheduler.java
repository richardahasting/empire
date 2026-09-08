package org.hastingtx.empire.server.game;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The heartbeat. Polls every 10 s; each game's own interval decides whether it is due. */
@Component
@EnableScheduling
public class UpdateScheduler {
    private final GameService games;
    public UpdateScheduler(GameService games) { this.games = games; }

    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void tick() { games.tick(); }
}
