// Same-origin API under the /empire context path. Session token lives in localStorage
// (magic-link auth, 3-month TTL) and goes out as a Bearer header.
const BASE = "/empire/api";
const KEY = "empire-session";

export function getToken(): string | null {
  try { return localStorage.getItem(KEY); } catch { return null; }
}
export function setToken(t: string | null) {
  try { if (t) localStorage.setItem(KEY, t); else localStorage.removeItem(KEY); } catch { /* private mode */ }
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) { super(message); this.status = status; }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { "content-type": "application/json" };
  const t = getToken();
  if (t) headers["authorization"] = `Bearer ${t}`;
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let data: unknown = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
  if (!res.ok) {
    if (res.status === 401) setToken(null);
    const msg = (data as { error?: string } | null)?.error ?? `${res.status} ${res.statusText}`;
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

export const api = {
  get: <T,>(path: string) => call<T>("GET", path),
  post: <T,>(path: string, body?: unknown, method: "POST" | "PUT" | "DELETE" = "POST") => call<T>(method, path, body ?? {}),
};

// ---- shapes (mirror the server records) ----
export interface Coord { x: number; y: number }
export interface Me { id: number; email: string; name: string; admin: boolean }
export interface Levels { tech: number; research: number; education: number; happiness: number }
export interface Resources { fertility: number; minerals: number; gold: number; oil: number; uranium: number }
export interface SectorView {
  at: Coord; relative: Coord; full: boolean; terrain: string; elevation: number; owner: number;
  designation: string | null; efficiency: number; mobility: number; roadLevel: number; roadTarget: number; railLevel: number; railTarget: number;
  stock: Record<string, number>; thresholds: Record<string, number>; distCenter: Coord | null;
  held: Record<string, number>; resources: Resources | null;
  /** Standing delivery orders by commodity: above threshold, one hex in dir (e ne nw w sw se) every update. */
  deliveries: Record<string, { dir: string; threshold: number }>;
  /** A sanctuary, and whose (owner's name for a foreign sector; null for yours or nobody's). */
  sanctuary: boolean; ownerName: string | null;
  /**
   * On the chart from memory rather than from sight (issue #64): everything here is as it was at
   * `seenUpdate`, not as it is. `age` is updates since, so it can be dimmed by how stale it is.
   */
  remembered: boolean; seenUpdate: number; age: number;
}
export interface ShipView {
  id: number; cls: string; name: string; at: Coord; relative: Coord; efficiency: number; stock: Record<string, number>; load: number; hold: number;
  dest: Coord | null; destRelative: Coord | null; lane: { from: Coord; to: Coord; fromRelative: Coord; toRelative: Coord; cargo: string[]; outbound: boolean } | null;
  note: string; docked: boolean;
  /** Tech it was laid at, and how many sea hexes it makes per update now. */
  tech: number; hexesPerUpdate: number;
  /** "fish": roaming the grounds near homeRelative, landing the catch there. */
  mission: string | null; homeRelative: Coord | null;
}
export interface CountryView {
  countryId: number; name: string; updateNumber: number; capital: Coord; wrapX: boolean; wrapY: boolean; width: number; height: number; cash: number; btu: number;
  levels: Levels; inSanctuary: boolean; bankrupt: boolean; commodityIds: string[]; sectors: SectorView[]; otherCountryNames: string[];
  /** Your ships (issue #56). */
  ships: ShipView[];
  /** Standing depot-to-depot rail runs; empty cargo means "keep the far end's thresholds topped up" (issue #70). */
  railLanes: { from: Coord; to: Coord; fromRelative: Coord; toRelative: Coord; cargo: string[] }[];
  /** Trains stopped part-way along a line (issue #70). */
  trains: { at: Coord; relative: Coord; commodity: string; qty: number; dest: Coord; destRelative: Coord }[];
}
export interface CountrySeat { id: number; name: string; taken: boolean }
export interface GameSummary {
  id: number; name: string; preset: string; status: string; updateNumber: number; width: number; height: number;
  countries: CountrySeat[]; myCountry: number | null; intervalSeconds: number; nextUpdateAt: string | null;
}
export interface Projection {
  forUpdate: number; cashNow: number; cashAfter: number; civNow: number; civAfter: number; foodNow: number; foodAfter: number;
  btuNow: number; btuAfter: number; starvingSectors: number; spoilingSectors: number; flowsCompleted: number; flowsHeld: number;
}
export interface SectorType {
  id: string; glyph: string; category: string; maxPopulation: number; minTech: number | null;
  produces: Record<string, number> | null; consumes: Record<string, number> | null; build: Record<string, number> | null;
  terrainRequired: string[] | null; flags: string[] | null;
}
export interface Commodity { id: string; name: string; weight: number; priority: number; isPerson?: boolean | null }
export interface RoadRules { buildMaterialsPerPoint: Record<string, number>; workPerPoint: number; maxPointsPerUpdate: number; costMultiplierByTerrain: Record<string, number>; maxLevelByTerrain: Record<string, number>; decayPerUpdate: number; maintenanceCashPerPointPerUpdate: number }
export interface Crossing { techRequired: number; materials: Record<string, number> }
export interface RailRules { bridge?: Crossing | null; tunnel?: Crossing | null; techRequired: number; buildMaterialsPerPoint: Record<string, number>; maxPointsPerUpdate: number; minLevelToCarry: number; capacityPerUpdateAt100: number; cashPer100UnitsShipped: number; maxSectorsPerUpdate: { base: number; perTechPoint: number }; costMultiplierByTerrain: Record<string, number>; maxLevelByTerrain: Record<string, number>; decayPerUpdate: number; maintenanceCashPerPointPerUpdate: number }
export interface ShipClass { id: string; name: string; glyph: string; role: string; techRequired: number; build: Record<string, number> | null; hold: number; speed: number; fishingRate?: number | null; happinessPerEtu?: number | null; carries?: string[] | null }
export interface ShipsRules { startEfficiency: number; dockPointsPerUpdate: number; harborMinEfficiency: number; classes: ShipClass[] }
export interface Rules { sectorTypes: SectorType[]; commodities: Commodity[]; etusPerUpdate: number; btuCosts: Record<string, number>; road?: RoadRules; defaultCapacity?: number; rail?: RailRules; productionMinEfficiency?: number; massThresholdMultiplierByType?: Record<string, number>; ships?: ShipsRules | null }
export interface Outcome { accepted: boolean; error?: string; btuSpent: number; view: CountryView; info?: string | null }
export interface ConsoleReply { output: string; accepted: boolean; error?: string; view?: CountryView }
export interface CommandRequest {
  verb: string; x?: number; y?: number; x2?: number; y2?: number; type?: string; commodity?: string; amount?: number; clear?: boolean;
  /** Many sectors instead of x,y: "*" (all mine), "*:TYPE" (one designation), "x1:x2,y1:y2" (a rectangle, relative). Standing orders only. */
  scope?: string;
  /** deliver: e ne nw w sw se (or "none" to clear). */
  direction?: string;
  /** ships: which ship; lane cargo; build_ship name. */
  ship?: number; cargo?: string[]; name?: string;
}

/** A macro step: a panel command with the sector left blank (issue #47). */
export interface MacroStep { verb: string; commodity?: string; amount?: number; clear?: boolean; type?: string; direction?: string; center?: "capital" | { dx: number; dy: number } }
export interface Macro { slot: number; name: string; steps: MacroStep[]; summary?: string }
export const macrosApi = {
  list: () => api.get<Macro[]>("/macros"),
  save: (m: Macro) => api.post<Macro>(`/macros/${m.slot}`, { name: m.name, steps: m.steps }, "PUT"),
  remove: (slot: number) => api.post<{ slot: number }>(`/macros/${slot}`, {}, "DELETE"),
  run: (gameId: number, slot: number, at: Coord | null, scope?: string) => api.post<Outcome>(`/games/${gameId}/macros/${slot}/run`, { x: at?.x, y: at?.y, scope: scope || undefined }),
};

export interface Estimate {
  ok: boolean; error?: string; path: Coord[]; hopCosts: number[]; totalMobility: number; reach: number;
  arrivesQty: number; heldQty: number; holdsAt: Coord | null; available: number; sourceMobility: number;
}
export function estimate(gameId: number, q: { verb: "move" | "explore" | "rail" | "sail"; x: number; y: number; x2: number; y2: number; commodity?: string; amount: number; ship?: number }): Promise<Estimate> {
  const p = new URLSearchParams({ verb: q.verb, x: String(q.x), y: String(q.y), x2: String(q.x2), y2: String(q.y2), amount: String(q.amount) });
  if (q.commodity) p.set("commodity", q.commodity);
  if (q.ship !== undefined) p.set("ship", String(q.ship));
  return api.get<Estimate>(`/games/${gameId}/estimate?${p}`);
}

export interface FlowOut { kind: string; commodity: string; qtyPlanned: number; qtyMoved: number; path: Coord[]; hopsDelivered: number; completed: boolean; holdReason: string | null }
export interface LastUpdate { updateNumber: number; millis: number; events: Record<string, unknown>[]; flows: FlowOut[]; notes: Record<string, string[]> }
