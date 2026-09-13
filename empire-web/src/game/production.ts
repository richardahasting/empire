import type { Curve, Levels, Rules, SectorType, SectorView } from "@/api/client";

/**
 * What a sector will make at the next update and which of the four limits binds (issue #163) —
 * the guide's "four limits on production", worked for one hex. It mirrors ProductionStep: work ×
 * efficiency × resource gate × level curve gives the units of output the sector could make; the
 * inputs on hand scale that down. Construction's share of the work and the room left in storage
 * are ignored, so this is "roughly N", and says so.
 */
export interface Limit { name: "efficiency" | "work" | "resource" | "tech" | "inputs"; factor: number; note: string; ok: boolean }
export interface Estimate {
  makes: { id: string; perUpdate: number }[];
  raises: { level: string; perUpdate: number }[];
  inputs: { id: string; perUnit: number; stock: number; covers: number }[];
  gate: { resource: string; value: number; wording: string } | null;
  limits: Limit[];
  binding: Limit | null;
  producing: boolean;
}

/** The population ceiling for a sector: the type's flat cap, scaled by research the way Ctx.maxPopulation does (issue #153). */
export function popCeiling(t: SectorType, rules: Rules, research: number): { cap: number; scale: number } {
  const c = rules.maxPopCurve;
  let scale = 1;
  if (c && c.type === "res_pop") scale = 0.4 + 0.6 * (50 + 4 * research) / (200 + 3 * research);
  else if (c && c.type !== "none") scale = Math.min(c.cap ?? 2, (c.base ?? 1) + (c.perResearchPoint ?? 0) * research);
  return { cap: t.maxPopulation * scale, scale };
}

export function wording(value: number): string { return value >= 60 ? "rich" : value >= 30 ? "fair" : "poor"; }

/** The same formulas as CurveCfg.eval on the server; anything unknown counts as 1 so a new curve type cannot blank the panel. */
export function evalCurve(c: Curve | undefined, level: number): number {
  if (!c) return 1;
  switch (c.type) {
    case "wolfpack": { const m = c.min ?? 0, l = c.lag ?? 10, d = level - m; return d < 0 ? 0 : d / (d + l); }
    case "saturating": return (c.baseline ?? 0) + level / (level + (c.k ?? 1));
    case "log": return 1 + Math.log(1 + level * (c.easy ?? 1)) / Math.log(c.base ?? Math.E) / 10;
    case "constant": return c.value ?? 1;
    case "linear": { const a = c.at0 ?? 1, b = c.at100 ?? 1; return a + (b - a) * Math.max(0, Math.min(100, level)) / 100; }
    case "diminishing": { const x = Math.max(0, Math.min(100, level)) / 100, mm = c.minMultiplier ?? 0; return 1 - (1 - mm) * (1 - (1 - x) * (1 - x)); }
    default: return 1;
  }
}

export function estimate(s: SectorView, t: SectorType, rules: Rules, levels: Levels): Estimate | null {
  const produces = t.produces ?? {}, producesLevel = t.producesLevel ?? {}, consumes = t.consumes ?? {};
  if (Object.keys(produces).length === 0 && Object.keys(producesLevel).length === 0) return null;

  const w = rules.work;
  const civ = s.stock["civ"] ?? 0, uw = s.stock["uw"] ?? 0, mil = s.stock["mil"] ?? 0;
  const h = w?.happinessEffectCurve;
  const happy = h ? Math.max(h.min, Math.min(h.max, 1 + (levels.happiness - h.neutralAt) * h.slopePerPoint)) : 1;
  const work = (civ * (w?.perCiv ?? 1) + uw * (w?.perUw ?? 1) + mil * (w?.perMil ?? 0.5)) * rules.etusPerUpdate * happy;

  const floor = rules.productionMinEfficiency ?? 0;
  const effOk = s.efficiency >= floor;
  const eff = effOk ? s.efficiency / 100 : 0;

  let gate: Estimate["gate"] = null, gateFactor = 1;
  if (t.resourceGate) {
    const value = s.resources ? ((s.resources as unknown as Record<string, number>)[t.resourceGate] ?? 0) : 0;
    gate = { resource: t.resourceGate, value, wording: wording(value) };
    gateFactor = value / 100;
  }

  let levelFactor = 1, levelNote = "no level effect";
  if (t.levelEffect) {
    const lv = (levels as unknown as Record<string, number>)[t.levelEffect.level] ?? 0;
    levelFactor = evalCurve(rules.curves?.[t.levelEffect.curve], lv);
    levelNote = `${t.levelEffect.level} ${lv.toFixed(0)} → ×${levelFactor.toFixed(2)}`;
  }

  const unit = work * eff * gateFactor * levelFactor;
  const wants = Object.entries(produces).map(([id, rate]) => ({ id, want: unit * rate }));
  const wantLevels = Object.entries(producesLevel).map(([level, rate]) => ({ level, want: unit * rate }));
  const totalWant = wants.reduce((a, x) => a + x.want, 0) + wantLevels.reduce((a, x) => a + x.want, 0);

  let scale = 1;
  const inputs = Object.entries(consumes).map(([id, perUnit]) => {
    const stock = s.stock[id] ?? 0;
    const need = totalWant * perUnit;
    const covers = perUnit > 0 ? stock / perUnit : Infinity;
    if (need > 0) scale = Math.min(scale, stock / need);
    return { id, perUnit, stock, covers };
  });

  const limits: Limit[] = [
    { name: "efficiency", factor: eff, ok: effOk, note: effOk ? `${s.efficiency.toFixed(0)}% — producing (floor ${floor}%)` : `${s.efficiency.toFixed(0)}% — nothing below ${floor}%` },
    { name: "work", factor: work > 0 ? 1 : 0, ok: work > 0, note: work > 0 ? `${Math.round(work)} from ${Math.round(civ + uw + mil)} people` : "nobody here — nothing gets made" },
  ];
  if (gate) limits.push({ name: "resource", factor: gateFactor, ok: gateFactor > 0, note: `${gate.resource} ${gate.value} / 100 — ${gate.wording} ground for a ${t.id.replace(/_/g, " ")}` });
  else limits.push({ name: "resource", factor: 1, ok: true, note: `${t.category}: no gate` });
  if (t.levelEffect) limits.push({ name: "tech", factor: levelFactor, ok: levelFactor > 0, note: levelNote });
  if (inputs.length) limits.push({ name: "inputs", factor: scale, ok: scale > 0, note: scale >= 1 ? "all inputs on hand" : scale <= 0 ? `none of ${inputs.filter(i => i.stock <= 0).map(i => i.id).join(", ")}` : `only ${Math.round(scale * 100)}% of what is wanted` });
  else limits.push({ name: "inputs", factor: 1, ok: true, note: "needs no inputs" });

  // the binding limit: a hard stop first (efficiency floor, nobody, no gate, no inputs), else the smallest factor
  const stop = limits.find(l => !l.ok);
  const binding = stop ?? [...limits].sort((a, b) => a.factor - b.factor)[0] ?? null;

  return {
    makes: wants.map(x => ({ id: x.id, perUpdate: x.want * scale })),
    raises: wantLevels.map(x => ({ level: x.level, perUpdate: x.want * scale })),
    inputs, gate, limits, binding, producing: unit > 0 && scale > 0 && totalWant > 0,
  };
}

/** For a sector that makes nothing: what would do well here, from its endowments. */
export function prospects(s: SectorView, rules: Rules): { type: SectorType; value: number; wording: string }[] {
  if (!s.resources) return [];
  const r = s.resources as unknown as Record<string, number>;
  return rules.sectorTypes
    .filter(t => t.resourceGate && t.produces && Object.keys(t.produces).length > 0 && (!t.terrainRequired || t.terrainRequired.includes(s.terrain)) && !(t.flags ?? []).includes("no_designate"))
    .map(t => ({ type: t, value: r[t.resourceGate!] ?? 0, wording: wording(r[t.resourceGate!] ?? 0) }))
    .sort((a, b) => b.value - a.value);
}
