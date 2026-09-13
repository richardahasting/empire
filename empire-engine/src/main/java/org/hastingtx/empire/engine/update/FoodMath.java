package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Commodities;
import org.hastingtx.empire.engine.model.Sector;

/**
 * How much a sector's people eat from stock in one update — the same arithmetic PopulationStep
 * uses, without the ledger, so a warning or a census column can say it before the update does.
 * Subsistence: the first {@code limit} people live off the land and draw nothing from stock.
 */
public final class FoodMath {
    private FoodMath() {}

    public static double eatsPerUpdate(GameConfig cfg, double civ, double mil, double uw, int fertility, boolean land) {
        EconomyCfg.PopulationCfg p = cfg.economy().population();
        double limit = land ? p.subsistenceOrNone().limit(fertility) : 0;
        double people = Math.max(0, civ) + Math.max(0, mil) + Math.max(0, uw);
        double fed = Math.min(people, limit);
        // whoever forages first, the same number of mouths are covered; rates differ by class only in principle
        double rate = people <= 0 ? 0 : (Math.max(0, civ) * p.foodPerCivPerEtu() + Math.max(0, mil) * p.foodPerMilPerEtu() + Math.max(0, uw) * p.foodPerUwPerEtu()) / people;
        return Math.max(0, people - fed) * rate * cfg.etus();
    }

    public static double eatsPerUpdate(GameConfig cfg, Commodities com, Sector s) {
        return eatsPerUpdate(cfg, s.stock().get(com.civ), s.stock().get(com.mil), s.stock().get(com.uw), s.fertility(), s.terrain().isLand());
    }
}
