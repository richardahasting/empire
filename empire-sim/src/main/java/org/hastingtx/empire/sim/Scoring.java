package org.hastingtx.empire.sim;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;

import java.util.Map;

/** The composite from scoring.weights. Never BTUs. */
public final class Scoring {
    private Scoring() {}

    public static double score(GameConfig cfg, World w, Country c) {
        Map<String, Double> wt = cfg.units().enabled() ? cfg.scoring().weightsWithUnits() : cfg.scoring().weights();
        Commodities com = Commodities.of(cfg);
        double civ = 0, eff = 0; int terr = 0;
        for (Sector s : w.sectors()) if (s.owner() == c.id()) { civ += s.stock().get(com.civ); eff += s.efficiency(); terr++; }
        double survival = terr > 0 && civ > 0 ? 1 : 0;
        return civ * wt.getOrDefault("civilians", 0.0) + eff * wt.getOrDefault("total_efficiency", 0.0) + c.levels().tech() * wt.getOrDefault("tech", 0.0)
                + c.cash() * wt.getOrDefault("treasury", 0.0) + terr * wt.getOrDefault("territory", 0.0) + survival * wt.getOrDefault("survival", 0.0);
    }
}
