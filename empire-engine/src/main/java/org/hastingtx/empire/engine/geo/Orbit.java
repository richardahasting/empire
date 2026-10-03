package org.hastingtx.empire.engine.geo;

import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Plane;
import org.hastingtx.empire.engine.model.World;

/**
 * A satellite's ground track (issue #71; KNOWN move_sat.c). Round one turn of its orbit it laps the world east
 * {@code orbit_laps} times and swings north and south {@code orbit_waves} times; each update it moves by how far the
 * track moves, from wherever it went up, so two satellites launched over different sectors keep their own tracks.
 */
public final class Orbit {
    private Orbit() {}

    /** Where the track is at {@code theta}, as the original computes it: whole sectors, truncated. */
    private static int x(UnitsCfg.MissilesCfg mc, World w, double theta) { return (int) (mc.orbitLaps() * theta * w.width()); }
    private static int y(UnitsCfg.MissilesCfg mc, World w, double theta) { return (int) (Math.sin(2 * Math.PI * mc.orbitWaves() * theta) * w.height() * mc.orbitAmplitude()); }

    /** One update further round: {@code orbit_step} of a turn, and over the sector the track moved it to. */
    public static Plane next(UnitsCfg.MissilesCfg mc, World w, Plane p) {
        double t = p.theta() + mc.orbitStep();
        if (t >= 1.0) t -= 1.0;
        int nx = p.at().x() + x(mc, w, t) - x(mc, w, p.theta());
        int ny = p.at().y() + y(mc, w, t) - y(mc, w, p.theta());
        if (t < p.theta()) nx += (int) (mc.orbitLaps() * w.width());   // round the end of a turn, the track carries on east
        // the original's world always wraps; an orbit goes round the planet whatever the map does, so east-west it always
        // wraps here too, and north-south a world without wrap holds it at the edge
        int x = Math.floorMod(nx, w.width());
        int y = w.wrapY() ? Math.floorMod(ny, w.height()) : Math.max(0, Math.min(w.height() - 1, ny));
        return p.orbited(new Coord(x, y), t);
    }
}
