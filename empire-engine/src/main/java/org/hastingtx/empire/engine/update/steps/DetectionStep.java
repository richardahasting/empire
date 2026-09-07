package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Step;

/** Step 10: probabilistic radar. M3. Adjacent-sector visibility is handled by CountryView. */
public final class DetectionStep implements Step {
    public String name() { return "detection"; }
    public void run(Ctx ctx) { /* M3 */ }
}
