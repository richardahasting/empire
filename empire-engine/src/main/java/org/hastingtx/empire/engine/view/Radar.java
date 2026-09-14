package org.hastingtx.empire.engine.view;

import org.hastingtx.empire.engine.config.DetectionCfg;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.model.Sector;

/**
 * How far a radar station reaches (issue #208). One answer for the two things radar does, so they cannot
 * disagree: the map it reveals ({@link Visibility}) and the enemy ships it detects (DetectionStep).
 *
 * <p>A station is a sector designated with the {@code radar_station} flag, and its strength is its
 * efficiency (Richard 2026-09-14: "I built one"). Reach is {@code nominal_range_at_100 × efficiency +
 * elevation bonus}, times {@code 1 + tech_bonus_per_point × tech}, capped at {@code max_range}. A sector
 * given a radar level by the deity still counts, at level × efficiency, as detection always read it.
 */
public final class Radar {
    private Radar() {}

    public static boolean station(GameConfig cfg, Sector s) {
        return s.owned() && cfg.sectorType(s.designation()).hasFlag("radar_station");
    }

    /** Reach in hexes, or 0 when the sector is no radar at all. */
    public static double range(GameConfig cfg, Sector s, double tech) {
        DetectionCfg d = cfg.detection();
        if (d == null || d.radar() == null || !s.owned()) return 0;
        DetectionCfg.RadarCfg r = d.radar();
        double strength = station(cfg, s) ? s.efficiency() / 100.0 : (s.radarLevel() / 100.0) * (s.efficiency() / 100.0);
        if (strength <= 0) return 0;
        double base = r.nominalRangeAt100() * strength + r.elevationBonusPer100m() * Math.max(0, s.elevation()) / 100.0;
        return Math.min(r.cap(), base * (1.0 + r.techBonusPerPoint() * Math.max(0, tech)));
    }
}
