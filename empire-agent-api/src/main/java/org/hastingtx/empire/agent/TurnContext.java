package org.hastingtx.empire.agent;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.update.Event;
import org.hastingtx.empire.engine.view.CountryView;

import java.util.List;

/**
 * Everything an agent gets for one turn. {@code rules} is the public game config (the
 * rulebook is not secret). {@code lastErrors} are rejections from its previous commands.
 * {@code scratchpad} is whatever it returned last turn; it has no other memory.
 */
public record TurnContext(
        CountryView view,
        GameConfig rules,
        List<Event> eventsSinceLastTurn,
        List<String> lastErrors,
        String scratchpad) {}
