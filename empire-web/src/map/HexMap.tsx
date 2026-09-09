import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Coord, CountryView, FlowOut, Rules, SectorView } from "@/api/client";
import { hexCenter, hexPath, neighbourAbs, pick, type Layout } from "./hex";
import { palette } from "./palette";

export type Layer = "ownership" | "designation" | "efficiency" | "mobility" | "stock" | "roads" | "rail";

interface Props {
  view: CountryView; rules: Rules; width: number; height: number;
  layer: Layer; stockCommodity: string; selected: Coord | null;
  onSelect: (c: Coord | null) => void;
  onContextMenu?: (c: Coord | null) => void;
  /** Called as the pointer crosses sectors (null when it leaves the grid). */
  onHover?: (c: Coord | null, clientX: number, clientY: number) => void;
  /** Route to highlight (absolute coords), e.g. the estimate for a move being picked. */
  highlightPath?: Coord[];
  /** Text to float next to the pointer. */
  tooltip?: string | null;
  /** Targeting mode: crosshair cursor and no drag-to-pan on click. */
  picking?: boolean;
  /** Last update's flows to animate, and where in the update (0..1) to draw them. */
  flows?: FlowOut[];
  flowT?: number;
}

/**
 * Canvas hex map. Draws only what the view contains (fog of war is the server's job).
 * Coordinates are absolute for drawing; labels show the country-relative form.
 */
export function HexMap({ view, rules, width, height, layer, stockCommodity, selected, onSelect, onContextMenu, onHover, highlightPath, tooltip, picking, flows, flowT }: Props) {
  const [mouse, setMouse] = useState<{ x: number; y: number } | null>(null);
  const lastHover = useRef<string | null>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const wrap = useRef<HTMLDivElement>(null);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const drag = useRef<{ x: number; y: number; px: number; py: number; moved: boolean } | null>(null);

  const byCoord = useMemo(() => {
    const m = new Map<string, SectorView>();
    for (const s of view.sectors) m.set(`${s.at.x},${s.at.y}`, s);
    return m;
  }, [view]);
  // Display frame: the capital sits at display cell (cx, cy) in the middle of the grid. Wrapping
  // axes are shifted so the country is never split across an edge; non-wrapping axes stay 1:1.
  // Row parity must be preserved when shifting y, or the odd-row stagger would flip.
  const frame = useMemo(() => {
    const cx = view.wrapX ? Math.floor(width / 2) : view.capital.x;
    let cy = view.wrapY ? Math.floor(height / 2) : view.capital.y;
    if (view.wrapY && ((cy - view.capital.y) & 1)) cy -= 1;
    return { cx, cy, shiftX: view.capital.x - cx, shiftY: view.capital.y - cy };
  }, [view, width, height]);
  const toWorld = useCallback((dx: number, dy: number): Coord => ({
    x: view.wrapX ? ((dx + frame.shiftX) % width + width) % width : dx,
    y: view.wrapY ? ((dy + frame.shiftY) % height + height) % height : dy,
  }), [view, frame, width, height]);
  const toDisplay = useCallback((c: Coord): Coord => ({
    x: view.wrapX ? ((c.x - frame.shiftX) % width + width) % width : c.x,
    y: view.wrapY ? ((c.y - frame.shiftY) % height + height) % height : c.y,
  }), [view, frame, width, height]);

  const typeCategory = useMemo(() => Object.fromEntries(rules.sectorTypes.map(t => [t.id, t.category])), [rules]);
  const typeGlyph = useMemo(() => Object.fromEntries(rules.sectorTypes.map(t => [t.id, t.glyph])), [rules]);

  const layout = useCallback((): Layout => {
    const el = wrap.current;
    const cw = el?.clientWidth ?? 800, ch = el?.clientHeight ?? 600;
    const sizeW = cw / (Math.sqrt(3) * (width + 0.5)), sizeH = ch / (1.5 * height + 0.5);
    const size = Math.max(6, Math.min(sizeW, sizeH)) * zoom;
    // centre the drawing on the capital
    const w = Math.sqrt(3) * size;
    const originX = cw / 2 - (w * (frame.cx + (frame.cy & 1 ? 0.5 : 0)) + w / 2) + pan.x;
    const originY = ch / 2 - (size * 1.5 * frame.cy + size) + pan.y;
    return { size, originX, originY };
  }, [width, height, zoom, pan, frame]);

  useEffect(() => {
    const c = canvas.current, el = wrap.current;
    if (!c || !el) return;
    const dpr = window.devicePixelRatio || 1;
    c.width = el.clientWidth * dpr; c.height = el.clientHeight * dpr;
    c.style.width = el.clientWidth + "px"; c.style.height = el.clientHeight + "px";
    const ctx = c.getContext("2d")!;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const p = palette();
    const l = layout();
    ctx.fillStyle = p.background; ctx.fillRect(0, 0, el.clientWidth, el.clientHeight);
    const maxStock = Math.max(1, ...view.sectors.filter(s => s.full).map(s => s.stock[stockCommodity] ?? 0));

    for (let dy = 0; dy < height; dy++) for (let dx = 0; dx < width; dx++) {
      const wc = toWorld(dx, dy);
      const s = byCoord.get(`${wc.x},${wc.y}`);
      const { cx, cy } = hexCenter(dx, dy, l);
      hexPath(ctx, cx, cy, l.size - 0.5);
      if (!s) { ctx.fillStyle = p.grid; ctx.globalAlpha = 0.25; ctx.fill(); ctx.globalAlpha = 1; continue; }
      ctx.fillStyle = p.terrain[s.terrain] ?? p.grid; ctx.fill();
      // overlay by layer
      let overlay: string | null = null, alpha = 0.55;
      if (s.full) {
        if (layer === "ownership") overlay = p.owner(s.owner, true);
        else if (layer === "designation") { const cat = typeCategory[s.designation ?? ""]; overlay = cat && cat !== "special" ? p.designation(cat) : null; }
        else if (layer === "efficiency") { overlay = p.accent; alpha = 0.05 + 0.7 * (s.efficiency / 100); }
        else if (layer === "mobility") { overlay = p.accent; alpha = 0.05 + 0.7 * Math.min(1, s.mobility / 127); }
        else if (layer === "stock") { overlay = p.accent; alpha = 0.05 + 0.7 * ((s.stock[stockCommodity] ?? 0) / maxStock); }
        else if (layer === "roads") { if (s.roadLevel > 0 || s.roadTarget > 0) { overlay = p.accent; alpha = 0.1 + 0.35 * (s.roadLevel / 100); } }
        else if (layer === "rail") { if (s.railLevel > 0 || s.railTarget > 0) { overlay = p.accent; alpha = 0.1 + 0.6 * (s.railLevel / 100); } }
      } else if (s.owner >= 0) { overlay = p.owner(s.owner, false); alpha = 0.45; }
      if (overlay) { ctx.globalAlpha = alpha; ctx.fillStyle = overlay; ctx.fill(); ctx.globalAlpha = 1; }
      ctx.strokeStyle = p.grid; ctx.lineWidth = 1; ctx.stroke();
    }
    if (layer === "roads") {
      // links between adjacent sectors that both have road: a network you can read at a glance
      ctx.strokeStyle = p.text; ctx.lineCap = "round";
      for (const s of view.sectors) {
        if (!s.full || s.roadLevel <= 0) continue;
        const a = toDisplay(s.at); const ca = hexCenter(a.x, a.y, l);
        for (let d = 0; d < 6; d++) {
          const nb = neighbourAbs(s.at, d, width, height, view.wrapX, view.wrapY);
          if (!nb) continue;
          const o = byCoord.get(`${nb.x},${nb.y}`);
          if (!o || !o.full || o.roadLevel <= 0) continue;
          if (o.at.y < s.at.y || (o.at.y === s.at.y && o.at.x < s.at.x)) continue;   // draw each pair once
          const b = toDisplay(o.at); const cb = hexCenter(b.x, b.y, l);
          ctx.lineWidth = Math.max(1, l.size * 0.12 * Math.min(s.roadLevel, o.roadLevel) / 100 + 1);
          ctx.beginPath(); ctx.moveTo(ca.cx, ca.cy); ctx.lineTo(cb.cx, cb.cy); ctx.stroke();
        }
        if (s.roadTarget > s.roadLevel) { ctx.setLineDash([3, 3]); hexPath(ctx, ca.cx, ca.cy, l.size * 0.6); ctx.lineWidth = 1; ctx.stroke(); ctx.setLineDash([]); }
      }
    }
    if (layer === "rail") {
      // track between adjacent rail-capable sectors; a sector with track below the carrying level is flagged red
      const minLevel = rules.rail?.minLevelToCarry ?? 20;
      const depotTypes = new Set(rules.sectorTypes.filter(t => (t.flags ?? []).includes("rail_endpoint")).map(t => t.id));
      ctx.lineCap = "round";
      for (const s of view.sectors) {
        if (!s.full) continue;
        const a = toDisplay(s.at); const ca = hexCenter(a.x, a.y, l);
        if (s.railLevel > 0 && s.railLevel < minLevel) { ctx.strokeStyle = "oklch(0.6 0.22 25)"; ctx.setLineDash([2, 3]); ctx.lineWidth = 2; hexPath(ctx, ca.cx, ca.cy, l.size * 0.55); ctx.stroke(); ctx.setLineDash([]); }
        if (s.railLevel < minLevel) continue;
        for (let d = 0; d < 6; d++) {
          const nb = neighbourAbs(s.at, d, width, height, view.wrapX, view.wrapY);
          if (!nb) continue;
          const o = byCoord.get(`${nb.x},${nb.y}`);
          if (!o || !o.full || o.railLevel < minLevel) continue;
          if (o.at.y < s.at.y || (o.at.y === s.at.y && o.at.x < s.at.x)) continue;
          const b = toDisplay(o.at); const cb = hexCenter(b.x, b.y, l);
          ctx.strokeStyle = p.text; ctx.lineWidth = Math.max(2, l.size * 0.14);
          ctx.beginPath(); ctx.moveTo(ca.cx, ca.cy); ctx.lineTo(cb.cx, cb.cy); ctx.stroke();
          ctx.strokeStyle = p.background; ctx.lineWidth = Math.max(1, l.size * 0.05); ctx.setLineDash([l.size * 0.15, l.size * 0.15]);
          ctx.beginPath(); ctx.moveTo(ca.cx, ca.cy); ctx.lineTo(cb.cx, cb.cy); ctx.stroke(); ctx.setLineDash([]);
        }
        if (depotTypes.has(s.designation ?? "")) { ctx.strokeStyle = p.text; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(ca.cx, ca.cy, l.size * 0.45, 0, Math.PI * 2); ctx.stroke(); }
        if (s.railTarget > s.railLevel) { ctx.setLineDash([3, 3]); ctx.strokeStyle = p.muted; hexPath(ctx, ca.cx, ca.cy, l.size * 0.6); ctx.lineWidth = 1; ctx.stroke(); ctx.setLineDash([]); }
      }
    }
    // Labels, road gauges and held markers go on top of the network lines so a level stays readable.
    for (let dy = 0; dy < height; dy++) for (let dx = 0; dx < width; dx++) {
      const wc = toWorld(dx, dy);
      const s = byCoord.get(`${wc.x},${wc.y}`);
      if (!s) continue;
      const { cx, cy } = hexCenter(dx, dy, l);
      if (!s.full) {
        // a neighbour that is somebody's sanctuary is marked as such (the original's 's')
        if (s.sanctuary && l.size >= 11) { ctx.fillStyle = p.text; ctx.textAlign = "center"; ctx.textBaseline = "middle"; ctx.font = `${Math.max(9, l.size * 0.7)}px ui-monospace, monospace`; ctx.fillText("s", cx, cy); }
        continue;
      }
      if (l.size >= 11) {
        // on the roads layer the label is the road level itself, where there is one to read
        const roadLabel = layer === "roads" && s.roadLevel > 0;
        const g = s.at.x === view.capital.x && s.at.y === view.capital.y ? "c" : (typeGlyph[s.designation ?? ""] ?? "?");
        ctx.fillStyle = p.text; ctx.textAlign = "center"; ctx.textBaseline = "middle";
        ctx.font = `${Math.max(9, l.size * (roadLabel ? (s.roadLevel >= 100 ? 0.55 : 0.7) : 0.9))}px ui-monospace, monospace`;
        if (roadLabel) {   // halo so the number survives the network lines converging under it
          ctx.strokeStyle = p.background; ctx.lineWidth = Math.max(2, l.size * 0.18); ctx.lineJoin = "round";
          ctx.strokeText(s.roadLevel.toFixed(0), cx, cy);
        }
        ctx.fillText(roadLabel ? s.roadLevel.toFixed(0) : g, cx, cy);
      }
      if (s.roadLevel > 0 || s.roadTarget > 0) drawRoadGauge(ctx, cx, cy, l.size, s.roadLevel, s.roadTarget, p);
      if (Object.keys(s.held).length > 0 && l.size >= 8) { ctx.fillStyle = p.muted; ctx.beginPath(); ctx.arc(cx + l.size * 0.45, cy - l.size * 0.45, Math.max(2, l.size * 0.15), 0, Math.PI * 2); ctx.fill(); }
    }
    if (flows && flows.length) drawFlows(ctx, flows, flowT ?? 1, p, l, toDisplay);
    if (highlightPath && highlightPath.length > 1) {
      ctx.strokeStyle = p.accent; ctx.lineWidth = Math.max(2, l.size * 0.18); ctx.lineCap = "round"; ctx.lineJoin = "round";
      ctx.beginPath();
      highlightPath.forEach((c, i) => { const d = toDisplay(c); const { cx, cy } = hexCenter(d.x, d.y, l); if (i === 0) ctx.moveTo(cx, cy); else ctx.lineTo(cx, cy); });
      ctx.stroke();
      const end = toDisplay(highlightPath[highlightPath.length - 1]); const { cx, cy } = hexCenter(end.x, end.y, l);
      ctx.fillStyle = p.accent; ctx.beginPath(); ctx.arc(cx, cy, Math.max(3, l.size * 0.22), 0, Math.PI * 2); ctx.fill();
    }
    if (selected) {
      const d = toDisplay(selected);
      const { cx, cy } = hexCenter(d.x, d.y, l);
      hexPath(ctx, cx, cy, l.size - 0.5); ctx.strokeStyle = p.ring; ctx.lineWidth = 3; ctx.stroke();
    }
  }, [view, byCoord, layer, stockCommodity, selected, width, height, layout, typeCategory, typeGlyph, toWorld, toDisplay, highlightPath, flows, flowT]);

  useEffect(() => {
    const el = wrap.current; if (!el) return;
    const ro = new ResizeObserver(() => setPan(p => ({ ...p })));
    ro.observe(el); return () => ro.disconnect();
  }, []);

  const onDown = (e: React.MouseEvent) => { drag.current = { x: e.clientX, y: e.clientY, px: pan.x, py: pan.y, moved: false }; };
  const onMove = (e: React.MouseEvent) => {
    const r = canvas.current!.getBoundingClientRect();
    setMouse({ x: e.clientX - r.left, y: e.clientY - r.top });
    if (onHover) {
      const hit = pick(e.clientX - r.left, e.clientY - r.top, layout(), width, height);
      const c = hit ? toWorld(hit.x, hit.y) : null;
      const key = c ? `${c.x},${c.y}` : "";
      if (key !== lastHover.current) { lastHover.current = key; onHover(c, e.clientX, e.clientY); }
    }
    const d = drag.current; if (!d) return;
    const dx = e.clientX - d.x, dy = e.clientY - d.y;
    if (Math.abs(dx) + Math.abs(dy) > 3) d.moved = true;
    if (d.moved) setPan({ x: d.px + dx, y: d.py + dy });
  };
  const onUp = (e: React.MouseEvent) => {
    const d = drag.current; drag.current = null;
    if (d && d.moved) return;
    const r = canvas.current!.getBoundingClientRect();
    const hit = pick(e.clientX - r.left, e.clientY - r.top, layout(), width, height);
    onSelect(hit ? toWorld(hit.x, hit.y) : null);
  };
  const onCtx = (e: React.MouseEvent) => {
    // do not preventDefault: Radix ContextMenu.Trigger listens for this same event to open the menu
    const r = canvas.current!.getBoundingClientRect();
    const hit = pick(e.clientX - r.left, e.clientY - r.top, layout(), width, height);
    const c = hit ? toWorld(hit.x, hit.y) : null;
    onSelect(c); onContextMenu?.(c);
  };
  const onWheel = (e: React.WheelEvent) => { setZoom(z => Math.max(0.4, Math.min(6, z * (e.deltaY < 0 ? 1.15 : 0.87)))); };

  return (
    <div ref={wrap} className="relative h-full w-full overflow-hidden rounded-lg border border-border bg-background">
      <canvas ref={canvas} className="absolute inset-0 block cursor-crosshair" onMouseDown={onDown} onMouseMove={onMove} onMouseUp={onUp} onMouseLeave={() => { drag.current = null; setMouse(null); lastHover.current = null; onHover?.(null, 0, 0); }} onWheel={onWheel} onContextMenu={onCtx} style={picking ? { cursor: "cell" } : undefined} />
      {tooltip && mouse && (
        <div className="pointer-events-none absolute z-10 rounded-md border border-border bg-popover px-2 py-1 text-xs text-popover-foreground shadow-md whitespace-pre"
             style={{ left: mouse.x + 14, top: mouse.y + 14 }}>{tooltip}</div>
      )}
    </div>
  );
}

/**
 * Road-level gauge at the foot of a hex, drawn on every layer so a road reads as part of the terrain.
 * A track spans the full 0..100 range, the filled part is the current level, and a hollow extension
 * marks a standing order not yet paved (level -> target). Levels are percentages (config/schema.yaml).
 */
function drawRoadGauge(ctx: CanvasRenderingContext2D, cx: number, cy: number, size: number, level: number, target: number, p: ReturnType<typeof palette>) {
  if (size < 8) return;
  const half = size * 0.42, h = Math.max(2, size * 0.12), y = cy + size * 0.66 - h / 2, x0 = cx - half;
  const px = (v: number) => (Math.max(0, Math.min(100, v)) / 100) * half * 2;
  ctx.save();
  ctx.globalAlpha = 0.35; ctx.fillStyle = p.muted; ctx.fillRect(x0, y, half * 2, h);
  ctx.globalAlpha = 1; ctx.fillStyle = p.text; ctx.fillRect(x0, y, px(level), h);
  if (target > level) { ctx.globalAlpha = 0.8; ctx.strokeStyle = p.text; ctx.lineWidth = 1; ctx.strokeRect(x0 + px(level) + 0.5, y + 0.5, Math.max(1, px(target) - px(level) - 1), Math.max(1, h - 1)); }
  ctx.restore();
}

/**
 * Flow animation. Each flow is a polyline through hex centres. The delivered part (hopsDelivered
 * hops) carries a particle stream whose thickness grows with quantity (log scale) and whose colour
 * is the commodity's. t in 0..1 scrubs the update: particles are spread along the delivered path
 * and advance with t, so a stalled shipment visibly ends short, with a marker where it holds.
 */
function drawFlows(ctx: CanvasRenderingContext2D, flows: FlowOut[], t: number, p: ReturnType<typeof palette>, l: Layout, toDisplay: (c: Coord) => Coord) {
  ctx.save();
  ctx.lineCap = "round"; ctx.lineJoin = "round";
  for (const f of flows) {
    if (f.qtyMoved <= 0 || f.path.length < 2) continue;
    const pts = f.path.map(c => { const d = toDisplay(c); const { cx, cy } = hexCenter(d.x, d.y, l); return { x: cx, y: cy }; });
    const delivered = Math.min(f.hopsDelivered, pts.length - 1);
    if (delivered < 1) continue;
    const width = Math.max(1.5, Math.min(l.size * 0.6, 1.5 + Math.log10(1 + f.qtyMoved) * l.size * 0.12));
    const colour = p.commodity(f.commodity);
    // faint full route; the delivered part is what the particles ride
    ctx.globalAlpha = 0.25; ctx.strokeStyle = colour; ctx.lineWidth = width;
    ctx.beginPath(); ctx.moveTo(pts[0].x, pts[0].y); for (let i = 1; i <= delivered; i++) ctx.lineTo(pts[i].x, pts[i].y); ctx.stroke();
    if (!f.completed) {   // hold marker at the last sector reached
      ctx.globalAlpha = 0.9; ctx.fillStyle = colour;
      const e = pts[delivered]; ctx.beginPath(); ctx.arc(e.x, e.y, width * 1.2, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = p.text; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(e.x - width * 1.6, e.y - width * 1.6); ctx.lineTo(e.x + width * 1.6, e.y + width * 1.6); ctx.stroke();
    }
    // particles: n evenly spaced, each advanced by t along the delivered polyline
    const segs: number[] = []; let total = 0;
    for (let i = 0; i < delivered; i++) { const d = Math.hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y); segs.push(d); total += d; }
    const n = Math.max(2, Math.min(12, Math.round(total / (l.size * 0.9))));
    ctx.globalAlpha = 0.95; ctx.fillStyle = colour;
    for (let k = 0; k < n; k++) {
      const u = ((k / n) + t) % 1;           // position along the path, 0..1, rolling with t
      let dist = u * total, i = 0;
      while (i < segs.length - 1 && dist > segs[i]) { dist -= segs[i]; i++; }
      const a = pts[i], b = pts[i + 1], r = segs[i] > 0 ? dist / segs[i] : 0;
      const x = a.x + (b.x - a.x) * r, y = a.y + (b.y - a.y) * r;
      ctx.beginPath(); ctx.arc(x, y, width * 0.55, 0, Math.PI * 2); ctx.fill();
    }
  }
  ctx.restore();
}
