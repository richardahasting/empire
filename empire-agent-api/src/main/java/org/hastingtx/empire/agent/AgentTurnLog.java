package org.hastingtx.empire.agent;

import java.util.List;

/** One logged agent turn, enough to audit or replay it. */
public record AgentTurnLog(
        int countryId,
        long updateNumber,
        String agentKind,
        int commandsIssued,
        int commandsAccepted,
        List<String> errors,
        double btuSpent,
        long wallMillis) {}
