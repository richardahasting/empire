package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;

/**
 * "What happens to me at the next update if nothing changes?" — answered by actually running
 * the update on the current world (it is a pure function) and diffing one country. Exact up
 * to the seed; other countries' pending commands are, of course, unknown.
 */
public final class Projection {
    private Projection() {}

    public record Result(long forUpdate, double cashNow, double cashAfter, double civNow, double civAfter, double foodNow, double foodAfter,
                         double btuNow, double btuAfter, int starvingSectors, int spoilingSectors, int flowsCompleted, int flowsHeld) {}

    public static Result of(World w, GameConfig cfg, int countryId, long seed) {
        Commodities com = Commodities.of(cfg);
        UpdateResult r = Update.run(w, cfg, seed);
        Country before = w.country(countryId), after = r.next().country(countryId);
        double civ0 = 0, civ1 = 0, food0 = 0, food1 = 0;
        for (Sector s : w.sectors()) if (s.owner() == countryId) { civ0 += s.stock().get(com.civ); food0 += s.stock().get(com.food); }
        for (Sector s : r.next().sectors()) if (s.owner() == countryId) { civ1 += s.stock().get(com.civ); food1 += s.stock().get(com.food); }
        int starving = 0, spoiling = 0, done = 0, held = 0;
        for (Event e : r.events()) if (e.country() == countryId) { if (e.type().equals("starvation")) starving++; else if (e.type().equals("spoilage")) spoiling++; }
        for (Flow f : r.flows()) if (f.owner() == countryId) { if (f.completed()) done++; else held++; }
        return new Result(w.updateNumber() + 1, before.cash(), after.cash(), civ0, civ1, food0, food1, before.btu(), after.btu(), starving, spoiling, done, held);
    }
}
