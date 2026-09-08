// The one place map colours live. Reads the design tokens at draw time so the map follows
// light/dark mode; the few map-only semantic colours are defined here, not in the renderer.
function token(name: string, fallback: string): string {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

export interface Palette {
  background: string; grid: string; text: string; muted: string; accent: string; ring: string;
  terrain: Record<string, string>;
  owner: (id: number, mine: boolean) => string;
  designation: (category: string) => string;
  commodity: (id: string) => string;
}

// Map-only hues, as oklch so they harmonise with the token set. Terrain is deliberately
// desaturated so ownership and designation overlays read on top of it.
const TERRAIN_LIGHT: Record<string, string> = {
  ocean: "oklch(0.86 0.04 240)", wilderness: "oklch(0.86 0.03 90)", plains: "oklch(0.90 0.06 120)",
  forest: "oklch(0.78 0.07 145)", swamp: "oklch(0.80 0.05 170)", mountain: "oklch(0.72 0.02 60)",
};
const TERRAIN_DARK: Record<string, string> = {
  ocean: "oklch(0.32 0.05 240)", wilderness: "oklch(0.36 0.03 90)", plains: "oklch(0.42 0.06 120)",
  forest: "oklch(0.34 0.06 145)", swamp: "oklch(0.36 0.05 170)", mountain: "oklch(0.30 0.02 60)",
};
const OWNER_HUES = [30, 200, 300, 130, 60, 260, 0, 170];
const CATEGORY_HUES: Record<string, number> = { extraction: 100, manufacturing: 40, infrastructure: 240, social: 300, special: 0 };
// One hue per commodity, fixed, so a stream reads the same on every map and in the legend.
export const COMMODITY_HUES: Record<string, number> = {
  civ: 30, mil: 0, uw: 45, food: 110, iron: 220, dust: 80, bar: 85, oil: 260, pet: 280, shell: 340, gun: 350, lcm: 190, hcm: 205, rad: 130,
};

export function palette(): Palette {
  const dark = document.documentElement.classList.contains("dark");
  return {
    background: token("--background", dark ? "#111" : "#fff"),
    grid: token("--border", dark ? "#444" : "#ddd"),
    text: token("--foreground", dark ? "#eee" : "#111"),
    muted: token("--muted-foreground", "#888"),
    accent: token("--primary", "#333"),
    ring: token("--ring", "#888"),
    terrain: dark ? TERRAIN_DARK : TERRAIN_LIGHT,
    owner: (id, mine) => mine ? `oklch(${dark ? 0.62 : 0.55} 0.16 ${OWNER_HUES[0]})` : `oklch(${dark ? 0.6 : 0.55} 0.14 ${OWNER_HUES[(id + 1) % OWNER_HUES.length]})`,
    designation: (category) => `oklch(${dark ? 0.7 : 0.5} 0.15 ${CATEGORY_HUES[category] ?? 0})`,
    commodity: (id) => `oklch(${dark ? 0.8 : 0.55} 0.19 ${COMMODITY_HUES[id] ?? 0})`,
  };
}
