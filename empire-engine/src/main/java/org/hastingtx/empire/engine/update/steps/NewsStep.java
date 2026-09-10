package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Step;

/** Step 11: world-visible summary. Events were emitted by the steps that caused them. */
public final class NewsStep implements Step {
    public String name() { return "news"; }
    public void run(Ctx ctx) {
        ctx.led.event("update", -1, null, "update " + (ctx.snap.updateNumber() + 1) + " complete", ctx.led.heldTotal);
    }
}
