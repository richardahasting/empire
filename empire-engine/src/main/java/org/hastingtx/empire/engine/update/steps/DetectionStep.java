package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.DetectionCfg;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.Step;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 10 (issue #75): probabilistic detection of enemy ships. Runs after the ship step, so it sees
 * where everything actually ended the update.
 *
 * <p>Order-independence: a country's sensors are combined as 1 − Π(1 − p), which is commutative, so
 * no result depends on the order sectors or ships are scanned in. The single roll per
 * (watcher, target, update) is drawn from a stream keyed on exactly those three, never on a
 * loop index.
 *
 * <p>A sighting that is not renewed is kept, ageing, until it passes
 * {@code detection.contact_staleness_updates} — the chart remembers where a ship was last seen, which
 * is not where it is now.
 */
public final class DetectionStep implements Step {
    public String name() { return "detection"; }

    public void run(Ctx ctx) {
        DetectionCfg d = ctx.cfg.detection();
        if (d == null || !"probabilistic".equals(d.model())) return;
        UnitsCfg.ShipsCfg sc = ctx.cfg.units() == null ? null : ctx.cfg.units().ships();
        long stamp = ctx.snap.updateNumber() + 1;          // the update number the result will carry

        List<Contact> out = new ArrayList<>();
        for (Country watcher : ctx.snap.countries()) {
            int cid = watcher.id();
            for (Ship target : ctx.ships) {
                if (target.owner() == cid) continue;
                double p = probability(ctx, d, sc, cid, watcher.levels().tech(), target);
                if (p <= 0) continue;
                double roll = Rng.stream("detect:" + cid + ":" + target.id() + ":" + stamp, ctx.seed).nextDouble();
                if (roll < p) out.add(new Contact(cid, target.id(), target.owner(), target.cls(), target.at(), stamp, Math.min(1.0, p)));
            }
        }
        // keep an unrenewed sighting until it goes stale; a fresh one always wins
        for (Contact old : ctx.contacts) {
            if (old.stale(stamp, d.contactStalenessUpdates())) continue;
            boolean renewed = false;
            for (Contact fresh : out) if (fresh.owner() == old.owner() && fresh.shipId() == old.shipId()) { renewed = true; break; }
            if (!renewed) out.add(old);
        }
        out.sort(java.util.Comparator.comparingInt(Contact::owner).thenComparingLong(Contact::shipId));
        ctx.contacts.clear();
        ctx.contacts.addAll(out);
    }

    /** The chance {@code cid} sees {@code target} this update, over every sensor it owns. */
    private static double probability(Ctx ctx, DetectionCfg d, UnitsCfg.ShipsCfg sc, int cid, double tech, Ship target) {
        double sig = signature(ctx, d, sc, target) * d.masking(ctx.snap.sector(target.at()).terrain().id());
        if (sig <= 0) return 0;
        double miss = 1.0;                                  // Π(1 − p) over sensors; commutative, so scan order cannot matter
        for (Sector s : ctx.snap.sectors()) {
            if (s.owner() != cid || s.radarLevel() <= 0) continue;
            double range = radarRange(d, s, tech);
            miss *= 1.0 - clamp(d.detectionProbability(Hex.distance(ctx.snap, s.at(), target.at()), range) * sig);
        }
        if (sc != null && d.ship() != null) for (Ship eye : ctx.ships) {
            if (eye.owner() != cid) continue;
            double range = d.ship().nominalRange() * eye.efficiency() / 100.0;
            miss *= 1.0 - clamp(d.detectionProbability(Hex.distance(ctx.snap, eye.at(), target.at()), range) * sig);
        }
        return clamp(1.0 - miss);
    }

    /** Nominal range × radar level × efficiency, plus height, all scaled by the country's tech. */
    static double radarRange(DetectionCfg d, Sector s, double tech) {
        DetectionCfg.RadarCfg r = d.radar();
        if (r == null) return 0;
        double base = r.nominalRangeAt100() * (s.radarLevel() / 100.0) * (s.efficiency() / 100.0)
                    + r.elevationBonusPer100m() * (s.elevation() / 100.0);
        return base * (1.0 + r.techBonusPerPoint() * tech);
    }

    /**
     * How visible this hull is. A submarine has its own (low) signature; otherwise a ship sharing its
     * hex with others of its own country reads as a fleet, and a lone hull as a single unit.
     */
    static double signature(Ctx ctx, DetectionCfg d, UnitsCfg.ShipsCfg sc, Ship target) {
        if (sc != null && "submarine".equals(sc.shipClass(target.cls()).role())) return d.signature("submarine");
        int company = 0;
        for (Ship other : ctx.ships) if (other.owner() == target.owner() && other.at().equals(target.at())) company++;
        return d.signature(company > 1 ? "fleet" : "single_unit");
    }

    private static double clamp(double p) { return p < 0 ? 0 : p > 1 ? 1 : p; }
}
