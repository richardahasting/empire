package org.hastingtx.empire.engine.view;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;

import java.util.Set;
import java.util.TreeSet;

/**
 * What a country can see right now (issue #64).
 *
 * <p>Lifted out of {@link CountryView} because the update needs the same answer: a sector is written
 * into a country's map memory exactly when that country can see it, and if the two disagreed you would
 * remember places you had never been or forget places you were standing in.
 */
public final class Visibility {
    private Visibility() {}

    /** Sectors {@code countryId} can see this instant: what it owns, what adjoins that, and what its ships overlook. */
    public static Set<Coord> of(World w, GameConfig cfg, int countryId) {
        Set<Coord> visible = new TreeSet<>();
        for (Sector s : w.ownedBy(countryId)) { visible.add(s.at()); visible.addAll(Hex.neighbours(w, s.at())); }
        // a ship lifts the fog around it (issue #62): everything within its class's sight is in view while it is there
        if (cfg.units().ships() != null) for (Ship sh : w.ships()) {
            if (sh.owner() != countryId) continue;
            int sight = cfg.units().ships().sightOf(cfg.units().ships().shipClass(sh.cls()));
            for (Sector s : w.sectors()) if (Hex.distance(w, s.at(), sh.at()) <= sight) visible.add(s.at());
        }
        return visible;
    }
}
