package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Step;
import org.hastingtx.empire.engine.view.Visibility;

import java.util.*;

/**
 * Step 10b: what each country can see this update goes onto its chart (issue #64).
 *
 * <p>The fog closes behind a ship, but the chart does not blank. A sector a country can see is
 * remembered as it is now, replacing whatever it remembered before; a sector it can no longer see keeps
 * the entry it already had, and ages. Nothing is ever forgotten — the original kept a map of everything
 * ever seen, and a chart that expired would be worse than no chart, because you could not tell the
 * difference between "nothing there" and "have not looked lately".
 *
 * <p>A country only remembers what a look would tell it: terrain, whose flag is over it, what it was
 * built as. Never stock, never mobility, never roads.
 */
public final class MemoryStep implements Step {
    public String name() { return "memory"; }

    public void run(Ctx ctx) {
        if (!ctx.cfg.options().mapMemoryOn()) return;
        // key by (country, sector index) so the newest look wins and the list stays one entry per pair
        Map<Long, SeenSector> byKey = new LinkedHashMap<>();
        for (SeenSector s : ctx.seen) byKey.put(key(ctx, s.owner(), s.at()), s);

        long now = ctx.snap.updateNumber() + 1;   // this update's number, the one the new world will carry
        for (Country c : ctx.snap.countries()) {
            for (Coord at : Visibility.of(ctx.snap, ctx.cfg, c.id())) {
                Sector s = ctx.snap.sector(at);
                byKey.put(key(ctx, c.id(), at), new SeenSector(c.id(), at, s.terrain(), s.owner(), s.designation(), now));
            }
        }
        // canonical order, so the state hash does not depend on when a sector was first seen
        List<SeenSector> out = new ArrayList<>(byKey.values());
        out.sort(Comparator.<SeenSector>comparingInt(SeenSector::owner).thenComparing(SeenSector::at));
        ctx.seen.clear();
        ctx.seen.addAll(out);
    }

    private static long key(Ctx ctx, int owner, Coord at) { return (long) owner * ctx.nSectors + ctx.idx(at); }
}
