package org.hastingtx.empire.engine.model;

import org.hastingtx.empire.engine.config.CommodityCfg;
import org.hastingtx.empire.engine.config.GameConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dense index over the config's commodity table. Stocks are double[] indexed by these
 * positions. The engine requires the ids civ, mil, uw, food to exist; everything else
 * is data.
 */
public final class Commodities {
    private final String[] ids;
    private final double[] weight;
    private final int[] priority;
    private final boolean[] person;
    private final double[] signature;
    private final CommodityCfg[] cfg;
    private final Map<String, Integer> index = new HashMap<>();

    public final int civ, mil, uw, food;

    public Commodities(List<CommodityCfg> table) {
        int n = table.size();
        ids = new String[n]; weight = new double[n]; priority = new int[n]; person = new boolean[n]; signature = new double[n]; cfg = new CommodityCfg[n];
        for (int i = 0; i < n; i++) {
            CommodityCfg c = table.get(i);
            if (index.put(c.id(), i) != null) throw new IllegalArgumentException("duplicate commodity id " + c.id());
            ids[i] = c.id(); weight[i] = c.weight(); priority[i] = c.priority(); person[i] = c.person(); signature[i] = c.signature(); cfg[i] = c;
        }
        civ = require("civ"); mil = require("mil"); uw = require("uw"); food = require("food");
    }

    public static Commodities of(GameConfig cfg) { return new Commodities(cfg.commodities()); }

    private int require(String id) {
        Integer i = index.get(id);
        if (i == null) throw new IllegalArgumentException("commodity table must define '" + id + "'");
        return i;
    }

    public int size() { return ids.length; }
    public String id(int i) { return ids[i]; }
    public int index(String id) {
        Integer i = index.get(id);
        if (i == null) throw new IllegalArgumentException("unknown commodity: " + id);
        return i;
    }
    public boolean has(String id) { return index.containsKey(id); }
    public double weight(int i) { return weight[i]; }
    /** Shipping weight per unit when leaving a sector of the given packing class (KNOWN: lbs / pkg). */
    public double weight(int i, String packingClass) { return weight[i] / cfg[i].pack(packingClass); }
    public int priority(int i) { return priority[i]; }
    public boolean isPerson(int i) { return person[i]; }
    public double signature(int i) { return signature[i]; }

    public double[] fromMap(Map<String, Double> m) {
        double[] q = new double[ids.length];
        if (m != null) for (var e : m.entrySet()) q[index(e.getKey())] = e.getValue();
        return q;
    }
}
