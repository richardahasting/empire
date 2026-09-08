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

/** Neighbour in direction d (0=E,1=NE,2=NW,3=W,4=SW,5=SE) in odd-r offset coords, honouring wrap; null when off a non-wrapping edge. */
export function neighbourAbs(c: { x: number; y: number }, d: number, width: number, height: number, wrapX: boolean, wrapY: boolean): { x: number; y: number } | null {
  const odd = c.y & 1;
  const even: [number, number][] = [[1, 0], [0, -1], [-1, -1], [-1, 0], [-1, 1], [0, 1]];
  const oddD: [number, number][] = [[1, 0], [1, -1], [0, -1], [-1, 0], [0, 1], [1, 1]];
  const [dx, dy] = (odd ? oddD : even)[d];
  let x = c.x + dx, y = c.y + dy;
  if (wrapX) x = ((x % width) + width) % width; else if (x < 0 || x >= width) return null;
  if (wrapY) y = ((y % height) + height) % height; else if (y < 0 || y >= height) return null;
  return { x, y };
}
