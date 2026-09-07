package org.hastingtx.empire.agent;

import org.hastingtx.empire.engine.command.Command;

import java.util.List;

public record TurnResult(List<Command> commands, String scratchpad) {
    public static TurnResult none() { return new TurnResult(List.of(), ""); }
}
