package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.model.Coord;

/**
 * Every player action, from the console, the web UI, or an agent. One record per verb.
 * {@code verb()} is the BTU cost key in economy.btu.cost_by_command.
 */
public sealed interface Command permits
        Command.BreakSanctuary, Command.Designate, Command.Threshold, Command.Distribute,
        Command.Move, Command.Explore {

    String verb();

    record BreakSanctuary() implements Command { public String verb() { return "break_sanctuary"; } }

    record Designate(Coord sector, String type) implements Command { public String verb() { return "designate"; } }

    /** Set (or clear with a negative amount) the distribution threshold for one commodity. */
    record Threshold(Coord sector, String commodity, double amount) implements Command { public String verb() { return "threshold"; } }

    /** Name a sector's distribution centre; null clears it. */
    record Distribute(Coord sector, Coord center) implements Command { public String verb() { return "distribute"; } }

    /** Queue a manual move, executed at the next update under the range-and-hold rule. */
    record Move(Coord from, Coord to, String commodity, double qty) implements Command { public String verb() { return "move"; } }

    /** Claim an adjacent unowned land sector by moving civilians into it. Immediate. */
    record Explore(Coord from, Coord to, double civs) implements Command { public String verb() { return "explore"; } }
}
