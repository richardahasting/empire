package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.model.World;

/** {@code error} is null on success. {@code info} is an optional note on success (e.g. a capped move). */
public record CommandResult(World world, String error, double btuSpent, String info) {
    public CommandResult(World world, String error, double btuSpent) { this(world, error, btuSpent, null); }
    public boolean ok() { return error == null; }
    static CommandResult fail(World w, String msg) { return new CommandResult(w, msg, 0, null); }
}
