import type { Coord, CountryView, SectorView } from "@/api/client";

// The engine's six directions, in its index order (Hex.DIRS): cube vectors for odd-r offset coordinates.
const DIRS: [string, [number, number]][] = [["e", [1, 0]], ["ne", [1, -1]], ["nw", [0, -1]], ["w", [-1, 0]], ["sw", [-1, 1]], ["se", [0, 1]]];

const toCube = (c: Coord): [number, number] => [c.x - (c.y - (c.y & 1)) / 2, c.y];
const toOffset = (q: number, r: number): Coord => ({ x: q + (r - (r & 1)) / 2, y: r });

/** An absolute coordinate as the player sees it: an offset from their capital, the short way round a wrapping map. */
export function relativeOf(view: CountryView, at: Coord): Coord {
  let dx = at.x - view.capital.x, dy = at.y - view.capital.y;
  if (view.wrapX) { if (dx > view.width / 2) dx -= view.width; else if (dx < -view.width / 2) dx += view.width; }
  if (view.wrapY) { if (dy > view.height / 2) dy -= view.height; else if (dy < -view.height / 2) dy += view.height; }
  return { x: dx, y: dy };
}

export const rel = (c: Coord) => `(${c.x},${c.y})`;

/**
 * Which of the six hex directions {@code to} lies in from {@code from}, both absolute — the nearest by
 * angle, so a centre ten hexes away and a little north of west reads "w". Null when they are the same
 * sector. Takes the nearest image of {@code to} on a wrapping map; heights are even, so row parity holds.
 */
export function bearing(view: CountryView, from: Coord, to: Coord): string | null {
  let best: Coord | null = null, bestD = Infinity;
  for (const kx of view.wrapX ? [-1, 0, 1] : [0]) for (const ky of view.wrapY ? [-1, 0, 1] : [0]) {
    const t = { x: to.x + kx * view.width, y: to.y + ky * view.height };
    const d = Math.hypot(t.x - from.x, t.y - from.y);
    if (d < bestD) { best = t; bestD = d; }
  }
  const [aq, ar] = toCube(from), [bq, br] = toCube(best!);
  const dq = bq - aq, dr = br - ar;
  if (dq === 0 && dr === 0) return null;
  const px = Math.sqrt(3) * (dq + dr / 2), py = 1.5 * dr;               // screen y grows downward
  const angle = Math.atan2(-py, px) * 180 / Math.PI;                     // e 0°, ne 60°, nw 120°, w 180°…
  return DIRS[((Math.round(angle / 60) % 6) + 6) % 6][0];
}

/** The neighbour one hex {@code dir} of {@code from}, absolute, wrapped onto the map. */
export function neighbour(view: CountryView, from: Coord, dir: string): Coord {
  const v = DIRS.find(d => d[0] === dir)?.[1] ?? [0, 0];
  const [q, r] = toCube(from);
  let c = toOffset(q + v[0], r + v[1]);
  if (view.wrapX) c = { ...c, x: ((c.x % view.width) + view.width) % view.width };
  if (view.wrapY) c = { ...c, y: ((c.y % view.height) + view.height) % view.height };
  return c;
}

/** "Surplus ↔ w (-10,-4)": where a sector's surplus goes and its shortages come from (Richard 2026-09-14). */
export function surplusLine(view: CountryView, at: Coord, centre: Coord | null): string {
  if (!centre) return "Surplus stays here (no distribution centre)";
  const b = bearing(view, at, centre);
  return b ? `Surplus ↔ ${b} ${rel(relativeOf(view, centre))}` : "Surplus ↔ here (this sector is its own centre)";
}

/** "→ w (-11,-4)": the neighbour a delivery order sends to. */
export function deliveryTarget(view: CountryView, at: Coord, dir: string): string {
  return `→ ${dir} ${rel(relativeOf(view, neighbour(view, at, dir)))}`;
}

/** Hex distance between two absolute sectors, the short way round a wrapping map (heights are even, so row parity holds). */
export function hexDistance(view: CountryView, a: Coord, b: Coord): number {
  let best = Infinity;
  const [aq, ar] = toCube(a);
  for (const kx of view.wrapX ? [-1, 0, 1] : [0]) for (const ky of view.wrapY ? [-1, 0, 1] : [0]) {
    const [bq, br] = toCube({ x: b.x + kx * view.width, y: b.y + ky * view.height });
    const dq = bq - aq, dr = br - ar;
    best = Math.min(best, (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2);
  }
  return best;
}

/**
 * The warehouse of yours nearest {@code from} — not {@code from} itself — by hex distance, ties to the lower (y, x);
 * null when you have none (issue #316: offered beside the capital as a distribution centre).
 */
export function nearestWarehouse(view: CountryView, from: Coord): { sector: SectorView; distance: number } | null {
  let best: { sector: SectorView; distance: number } | null = null;
  for (const s of view.sectors) {
    if (!s.full || s.designation !== "warehouse" || (s.at.x === from.x && s.at.y === from.y)) continue;
    const d = hexDistance(view, from, s.at);
    if (!best || d < best.distance || (d === best.distance && (s.at.y < best.sector.at.y || (s.at.y === best.sector.at.y && s.at.x < best.sector.at.x))))
      best = { sector: s, distance: d };
  }
  return best;
}
