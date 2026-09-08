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
  post: <T,>(path: string, body?: unknown) => call<T>("POST", path, body ?? {}),
};

// ---- shapes (mirror the server records) ----
export interface Coord { x: number; y: number }
export interface Me { id: number; email: string; name: string; admin: boolean }
export interface Levels { tech: number; research: number; education: number; happiness: number }
export interface Resources { fertility: number; minerals: number; gold: number; oil: number; uranium: number }
export interface SectorView {
  at: Coord; relative: Coord; full: boolean; terrain: string; elevation: number; owner: number;
  designation: string | null; efficiency: number; mobility: number; roadLevel: number;
  stock: Record<string, number>; thresholds: Record<string, number>; distCenter: Coord | null;
  held: Record<string, number>; resources: Resources | null;
}
export interface CountryView {
  countryId: number; name: string; updateNumber: number; capital: Coord; wrapX: boolean; wrapY: boolean; cash: number; btu: number;
  levels: Levels; inSanctuary: boolean; bankrupt: boolean; commodityIds: string[]; sectors: SectorView[]; otherCountryNames: string[];
}
export interface CountrySeat { id: number; name: string; taken: boolean }
export interface GameSummary {
  id: number; name: string; preset: string; status: string; updateNumber: number; width: number; height: number;
  countries: CountrySeat[]; myCountry: number | null;
}
export interface SectorType {
  id: string; glyph: string; category: string; maxPopulation: number; minTech: number | null;
  produces: Record<string, number> | null; consumes: Record<string, number> | null; build: Record<string, number> | null;
  terrainRequired: string[] | null; flags: string[] | null;
}
export interface Commodity { id: string; name: string; weight: number; priority: number }
export interface Rules { sectorTypes: SectorType[]; commodities: Commodity[]; etusPerUpdate: number; btuCosts: Record<string, number> }
export interface Outcome { accepted: boolean; error?: string; btuSpent: number; view: CountryView }
export interface ConsoleReply { output: string; accepted: boolean; error?: string; view?: CountryView }
export interface CommandRequest {
  verb: string; x?: number; y?: number; x2?: number; y2?: number; type?: string; commodity?: string; amount?: number; clear?: boolean;
}
