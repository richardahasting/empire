// Odd-r offset hex geometry for drawing. Pointy-top hexes; row y is shifted half a hex when odd.
export interface Layout { size: number; originX: number; originY: number }

export function hexCenter(x: number, y: number, l: Layout): { cx: number; cy: number } {
  const w = Math.sqrt(3) * l.size;
  const cx = l.originX + w * (x + (y & 1 ? 0.5 : 0)) + w / 2;
  const cy = l.originY + l.size * 1.5 * y + l.size;
  return { cx, cy };
}

export function hexPath(ctx: CanvasRenderingContext2D, cx: number, cy: number, size: number) {
  ctx.beginPath();
  for (let i = 0; i < 6; i++) {
    const a = (Math.PI / 180) * (60 * i - 30);
    const px = cx + size * Math.cos(a), py = cy + size * Math.sin(a);
    if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
  }
  ctx.closePath();
}

/** Pixel -> offset coordinate, by nearest centre (good enough for click picking). */
export function pick(px: number, py: number, l: Layout, width: number, height: number): { x: number; y: number } | null {
  let best: { x: number; y: number } | null = null, bd = Infinity;
  const yGuess = Math.round((py - l.originY - l.size) / (l.size * 1.5));
  for (let y = Math.max(0, yGuess - 1); y <= Math.min(height - 1, yGuess + 1); y++) {
    const w = Math.sqrt(3) * l.size;
    const xGuess = Math.round((px - l.originX - w / 2) / w - (y & 1 ? 0.5 : 0));
    for (let x = Math.max(0, xGuess - 1); x <= Math.min(width - 1, xGuess + 1); x++) {
      const { cx, cy } = hexCenter(x, y, l);
      const d = (cx - px) ** 2 + (cy - py) ** 2;
      if (d < bd) { bd = d; best = { x, y }; }
    }
  }
  return best && bd <= (l.size * 1.05) ** 2 ? best : null;
}
