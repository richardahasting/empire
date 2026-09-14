import { useMemo, useState } from "react";
import type { CommandRequest, Coord, CountryView, Rules } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";

interface Props {
  view: CountryView; rules: Rules; busy: boolean;
  /** The selected sectors, absolute; only ones this country holds. */
  area: Coord[];
  onCommand: (c: CommandRequest) => Promise<void>;
  onClear: () => void;
  /** Start picking a distribution centre on the map for every sector in the selection. */
  onPickCentre: () => void;
}

/**
 * Orders for many sectors at once (issue #191, Richard 2026-09-14): shift-drag a rectangle on the map,
 * then designate, point at a centre, set a threshold, or pave road or rail across the lot. Each order
 * goes to the server as one mass command over the listed sectors, so each sector pays its own BTU and a
 * sector that refuses (wrong terrain, not enough tech) is reported rather than stopping the rest.
 */
export function AreaActions({ view, rules, busy, area, onCommand, onClear, onPickCentre }: Props) {
  const keys = useMemo(() => new Set(area.map(c => `${c.x},${c.y}`)), [area]);
  const sectors = useMemo(() => view.sectors.filter(s => keys.has(`${s.at.x},${s.at.y}`)), [view, keys]);
  const byType = useMemo(() => {
    const m = new Map<string, number>();
    for (const s of sectors) m.set(s.designation ?? "—", (m.get(s.designation ?? "—") ?? 0) + 1);
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  }, [sectors]);
  // types a sector of any terrain might take; the server refuses the ones a given hex cannot, and says so
  const designatable = rules.sectorTypes.filter(t => !(t.flags ?? []).some(f => f === "no_designate" || f === "undesignated") && (t.minTech ?? 0) <= view.levels.tech);
  const [des, setDes] = useState("");
  const [commodity, setCommodity] = useState(view.commodityIds.includes("food") ? "food" : view.commodityIds[0]);
  const [amount, setAmount] = useState("");
  const [road, setRoad] = useState("100");
  const [rail, setRail] = useState("100");
  const all = { sectors: area };
  const cost = (verb: string) => (rules.btuCosts?.[verb] ?? rules.btuCosts?.default ?? 1) * area.length;

  return (
    <div className="space-y-3 text-sm">
      <div className="flex items-baseline justify-between gap-2">
        <div className="font-medium">{area.length} sector{area.length === 1 ? "" : "s"} selected</div>
        <Button size="sm" variant="ghost" onClick={onClear}>Clear (Esc)</Button>
      </div>
      <p className="text-xs text-muted-foreground">{byType.map(([t, n]) => `${n} ${t}`).join(" · ")}</p>
      <p className="text-xs text-muted-foreground">Each order goes to every selected sector; each pays its own BTU (about {cost("designate")} for most orders here). A sector that cannot take an order is skipped and named in the reply.</p>

      <div className="flex items-end gap-2">
        <label className="grid flex-1 gap-1 text-xs">Designate
          <Select value={des} onChange={e => setDes(e.target.value)}>
            <option value="">choose…</option>
            {designatable.map(t => <option key={t.id} value={t.id}>{t.glyph} {t.id}</option>)}
          </Select>
        </label>
        <Button size="sm" disabled={busy || !des} onClick={() => void onCommand({ verb: "designate", type: des, ...all })}>Designate</Button>
      </div>

      <div className="grid gap-1 text-xs">Distribution centre
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="secondary" disabled={busy} onClick={() => void onCommand({ verb: "distribute", x2: view.capital.x, y2: view.capital.y, ...all })}>→ capital</Button>
          <Button size="sm" variant="secondary" disabled={busy} onClick={onPickCentre}>Pick on map…</Button>
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "distribute", clear: true, ...all })}>Clear centre</Button>
        </div>
      </div>

      <div className="flex items-end gap-2">
        <label className="grid gap-1 text-xs">Threshold
          <Select value={commodity} onChange={e => setCommodity(e.target.value)} className="w-24">
            {view.commodityIds.map(c => <option key={c}>{c}</option>)}
          </Select>
        </label>
        <Input value={amount} onChange={e => setAmount(e.target.value)} inputMode="numeric" placeholder="amount" className="w-24" aria-label="threshold amount" />
        <Button size="sm" disabled={busy || amount === "" || !(Number(amount) >= 0)} onClick={() => void onCommand({ verb: "threshold", commodity, amount: Number(amount), ...all })}>Set</Button>
        <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "threshold", commodity, clear: true, ...all })}>Clear</Button>
      </div>
      <p className="text-xs text-muted-foreground">Goods thresholds scale by designation as they do for a typed rectangle (warehouses ×10); the reply says what each kind got.</p>

      <div className="flex items-end gap-2">
        <label className="grid gap-1 text-xs">Road to level
          <Input value={road} onChange={e => setRoad(e.target.value)} inputMode="numeric" className="w-20" />
        </label>
        <Button size="sm" disabled={busy || !(Number(road) >= 0)} onClick={() => void onCommand({ verb: "build_road", amount: Number(road), ...all })}>Build road</Button>
        <label className="grid gap-1 text-xs">Rail to level
          <Input value={rail} onChange={e => setRail(e.target.value)} inputMode="numeric" className="w-20" />
        </label>
        <Button size="sm" disabled={busy || !(Number(rail) >= 0)} onClick={() => void onCommand({ verb: "build_rail", amount: Number(rail), ...all })}>Build rail</Button>
      </div>
    </div>
  );
}
