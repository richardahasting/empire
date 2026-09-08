import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Coord, CountryView, Rules, SectorView } from "@/api/client";
import { hexCenter, hexPath, pick, type Layout } from "./hex";
import { palette } from "./palette";

export type Layer = "ownership" | "designation" | "efficiency" | "mobility" | "stock";

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
}

/**
 * Canvas hex map. Draws only what the view contains (fog of war is the server's job).
 * Coordinates are absolute for drawing; labels show the country-relative form.
 */
export function HexMap({ view, rules, width, height, layer, stockCommodity, selected, onSelect, onContextMenu, onHover, highlightPath, tooltip, picking }: Props) {
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
      } else if (s.owner >= 0) { overlay = p.owner(s.owner, false); alpha = 0.45; }
      if (overlay) { ctx.globalAlpha = alpha; ctx.fillStyle = overlay; ctx.fill(); ctx.globalAlpha = 1; }
      ctx.strokeStyle = p.grid; ctx.lineWidth = 1; ctx.stroke();
      if (l.size >= 11 && s.full) {
        ctx.fillStyle = p.text; ctx.font = `${Math.max(9, l.size * 0.9)}px ui-monospace, monospace`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
        const g = s.at.x === view.capital.x && s.at.y === view.capital.y ? "c" : (typeGlyph[s.designation ?? ""] ?? "?");
        ctx.fillText(g, cx, cy);
      }
      if (Object.keys(s.held).length > 0 && l.size >= 8) { ctx.fillStyle = p.muted; ctx.beginPath(); ctx.arc(cx + l.size * 0.45, cy - l.size * 0.45, Math.max(2, l.size * 0.15), 0, Math.PI * 2); ctx.fill(); }
    }
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
  }, [view, byCoord, layer, stockCommodity, selected, width, height, layout, typeCategory, typeGlyph, toWorld, toDisplay, highlightPath]);

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
