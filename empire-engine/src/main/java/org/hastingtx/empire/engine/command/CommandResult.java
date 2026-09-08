package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.model.World;

/** {@code error} is null on success. On failure {@code world} is the unchanged input. */
public record CommandResult(World world, String error, double btuSpent) {
    public boolean ok() { return error == null; }
    static CommandResult fail(World w, String msg) { return new CommandResult(w, msg, 0); }
}
