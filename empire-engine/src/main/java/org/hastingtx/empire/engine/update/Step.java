package org.hastingtx.empire.engine.update;

/** One numbered step of docs/update-sequence.md. Reads ctx.snap, writes ctx.led(). */
public interface Step {
    String name();
    void run(Ctx ctx);
}
