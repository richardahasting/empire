package org.hastingtx.empire.engine.update;

import org.hastingtx.empire.engine.config.EconomyCfg;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;

import java.util.ArrayList;
import java.util.List;

/** Read-only snapshot plus derived lookups shared by all steps. */
public final class Ctx {
    public final World snap;
    public final GameConfig cfg;
    public final Commodities com;
    public final Ledger led;
    public final int etus;
    public final long seed;
    public final List<List<Coord>> neighbours;
    /** Work (work-unit·ETUs) spent in step 4, subtracted from step 5's pool. */
    public final double[] workSpent;

    public Ctx(World snap, GameConfig cfg, Commodities com, long seed) {
        this.snap = snap; this.cfg = cfg; this.com = com; this.seed = seed;
        this.etus = cfg.etus();
        this.led = new Ledger(snap, com.size());
        this.workSpent = new double[snap.sectors().size()];
        this.neighbours = new ArrayList<>(snap.sectors().size());
        for (Sector s : snap.sectors()) neighbours.add(Hex.neighbours(snap, s.at()));
    }

    public int idx(Coord c) { return snap.index(c); }
    public Sector sector(int i) { return snap.sectors().get(i); }
    public Country country(int id) { return snap.countries().get(id); }
    public SectorTypeCfg type(Sector s) { return cfg.sectorType(s.designation()); }

    /** Storage cap for a non-person commodity in this sector. GUESS: independent of efficiency. */
    public double capacity(Sector s, int c) {
        SectorTypeCfg t = type(s);
        if (t.capacity() != null) {
            Double cap = t.capacity().get(com.id(c));
            if (cap != null) return cap;
        }
        return cfg.economy().defaultCapacity() * t.storeMultiplierOr1();
    }

    /** Population ceiling for this sector. KNOWN: flat per type (big cities aside); RES_POP scales it by research. */
    public double maxPopulation(Sector s) {
        double research = s.owned() ? country(s.owner()).levels().research() : 0;
        var pop = cfg.economy().population();
        double effScale = Boolean.TRUE.equals(pop.maxPopScalesWithEfficiency()) ? s.efficiency() / 100.0 : 1.0;
        return type(s).maxPopulation() * effScale * pop.maxPopResearchCurve().eval(research);
    }

    /** Shipping weight of one unit of c leaving sector s (packing applies at >= 60% efficiency, KNOWN). */
    public double weightLeaving(int c, Sector s) {
        String cls = s.efficiency() >= 60 ? type(s).packingOrNormal() : "inefficient";
        return com.weight(c, cls);
    }

    /** Mobility cost per weight-unit to move INTO sector s (terrain × efficiency × road). */
    public double moveCostInto(Sector s) {
        EconomyCfg.MobilityCfg m = cfg.economy().mobility();
        Double base = m.moveCostByTerrain().get(s.terrain().id());
        if (base == null) return Double.POSITIVE_INFINITY;
        double eff = m.efficiencyDiscount().eval(s.efficiency());
        double road = cfg.infrastructure().road().mobilityDiscountCurve().eval(s.roadLevel());
        return base * eff * road;
    }

    public double workAvailable(Sector s) {
        EconomyCfg.WorkCfg w = cfg.economy().work();
        double happiness = s.owned() ? country(s.owner()).levels().happiness() : 0;
        double raw = s.stock().get(com.civ) * w.perCiv() + s.stock().get(com.uw) * w.perUw() + s.stock().get(com.mil) * w.perMil();
        return raw * w.happinessEffectCurve().eval(happiness);
    }

    /**
     * Post-population work pool for the whole update, in work-unit·ETUs: people after this
     * update's births and deaths, times the ETUs, times the happiness curve, minus step 4's spend.
     */
    public double workAvailablePost(int i) {
        Sector s = sector(i);
        EconomyCfg.WorkCfg w = cfg.economy().work();
        double happiness = s.owned() ? country(s.owner()).levels().happiness() : 0;
        double civ = s.stock().get(com.civ) + led.stock[i][com.civ];
        double uw = s.stock().get(com.uw) + led.stock[i][com.uw];
        double mil = s.stock().get(com.mil) + led.stock[i][com.mil];
        double raw = Math.max(0, civ) * w.perCiv() + Math.max(0, uw) * w.perUw() + Math.max(0, mil) * w.perMil();
        return raw * etus * w.happinessEffectCurve().eval(happiness) - workSpent[i];
    }

    public double curve(String id, double level) {
        var c = cfg.economy().curves().get(id);
        if (c == null) throw new IllegalStateException("unknown curve: " + id);
        return c.eval(level);
    }
}
