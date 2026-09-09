import { useState } from "react";
import type { CommandRequest, CountryView, Rules, SectorView } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";

interface Props { sector: SectorView | null; view: CountryView; rules: Rules; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean }

/** Click a sector: what it is, what it holds, what it is short of, and the panel actions. */
export function Inspector({ sector: s, view, rules, onCommand, busy }: Props) {
  const [des, setDes] = useState("");
  const [thrCommodity, setThrCommodity] = useState("food");
  const [thrAmount, setThrAmount] = useState("");
  const [exploreCivs, setExploreCivs] = useState("20");

  if (!s) return <p className="text-sm text-muted-foreground">Click a sector to inspect it. Right-click one you own to move, explore, designate or set thresholds.</p>;
  const rel = `${s.relative.x},${s.relative.y}`;
  if (!s.full) {
    return (
      <div className="space-y-2 text-sm">
        <h3 className="font-semibold">Sector {rel}</h3>
        <p>{s.terrain}{s.owner >= 0 ? ` — held by ${view.otherCountryNames.length ? "another country" : "someone"}` : " — unowned"}</p>
        {s.owner < 0 && s.terrain !== "ocean" && (
          <div className="flex items-end gap-2">
            <label className="text-xs">civilians<Input value={exploreCivs} onChange={e => setExploreCivs(e.target.value)} className="w-20" /></label>
            <Button size="sm" disabled={busy} onClick={() => onCommand({ verb: "explore", x: view.capital.x, y: view.capital.y, x2: s.at.x, y2: s.at.y, amount: Number(exploreCivs) })}>Explore from capital</Button>
          </div>
        )}
        <p className="text-xs text-muted-foreground">Or right-click an owned neighbour and choose “Explore from here”.</p>
      </div>
    );
  }
  const type = rules.sectorTypes.find(t => t.id === s.designation);
  const short: string[] = [];
  if (type?.consumes) for (const [c] of Object.entries(type.consumes)) if ((s.stock[c] ?? 0) <= 0) short.push(c);
  if (type?.build) for (const [c] of Object.entries(type.build)) if (c !== "cash" && s.efficiency < 100 && (s.stock[c] ?? 0) <= 0) short.push(c + " (to build)");
  const designatable = rules.sectorTypes.filter(t => !(t.flags ?? []).some(f => f === "no_designate" || f === "undesignated") && (t.minTech ?? 0) <= view.levels.tech && (!t.terrainRequired || t.terrainRequired.includes(s.terrain)));

  return (
    <div className="space-y-3 text-sm">
      <h3 className="font-semibold">Sector {rel}</h3>
      <div className="flex flex-wrap gap-1">
        <Badge tone="accent">{s.designation}</Badge><Badge tone="muted">{s.terrain}</Badge>
        <Badge tone="neutral">eff {s.efficiency.toFixed(0)}%</Badge><Badge tone="neutral">mob {s.mobility.toFixed(0)}</Badge>
        {s.roadLevel > 0 && <Badge tone="neutral">road {s.roadLevel.toFixed(0)}</Badge>}
      </div>
      {s.resources && <p className="text-xs text-muted-foreground">fert {s.resources.fertility} · min {s.resources.minerals} · gold {s.resources.gold} · oil {s.resources.oil} · uran {s.resources.uranium}</p>}
      <table className="w-full text-xs">
        <thead><tr className="text-muted-foreground"><th className="text-left">commodity</th><th className="text-right">stock</th><th className="text-right">threshold</th><th className="text-right">deliver</th><th className="text-right">in transit</th></tr></thead>
        <tbody>
          {view.commodityIds.map(c => (
            <tr key={c} className={(s.stock[c] ?? 0) === 0 && s.thresholds[c] === undefined && !s.held[c] && !s.deliveries[c] ? "text-muted-foreground/60" : ""}>
              <td>{c}</td><td className="text-right tabular-nums">{fmt(s.stock[c])}</td>
              <td className="text-right tabular-nums">{s.thresholds[c] !== undefined ? fmt(s.thresholds[c]) : "—"}</td>
              <td className="text-right tabular-nums" title={s.deliveries[c] ? `above ${fmt(s.deliveries[c].threshold)}, one hex ${s.deliveries[c].dir} every update` : undefined}>{s.deliveries[c] ? `→${s.deliveries[c].dir} >${fmt(s.deliveries[c].threshold)}` : ""}</td>
              <td className="text-right tabular-nums">{s.held[c] ? fmt(s.held[c]) : ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {short.length > 0 && <p className="text-xs">Short of: <span className="text-destructive">{short.join(", ")}</span></p>}
      {(() => {
        const cap = rules.defaultCapacity ?? 9999;
        const full = view.commodityIds.filter(c => !["civ", "mil", "uw"].includes(c) && (s.stock[c] ?? 0) >= cap * 0.98);
        const flags: string[] = [];
        if (!s.distCenter) flags.push("no distribution centre — surplus stays here");
        else if (Object.keys(s.thresholds).length === 0) flags.push("centre set but no thresholds — nothing flows");
        if (full.length) flags.push(`at capacity: ${full.join(", ")} (production spoils)`);
        return flags.length ? <ul className="text-xs text-destructive">{flags.map(f => <li key={f}>{f}</li>)}</ul> : null;
      })()}
      <p className="text-xs">Distribution centre: {s.distCenter ? `${s.distCenter.x},${s.distCenter.y}` : "none"}
        {" "}<Button size="sm" variant="ghost" disabled={busy} onClick={() => onCommand({ verb: "distribute", x: s.at.x, y: s.at.y, x2: view.capital.x, y2: view.capital.y })}>→ capital</Button>
        {s.distCenter && <Button size="sm" variant="ghost" disabled={busy} onClick={() => onCommand({ verb: "distribute", x: s.at.x, y: s.at.y, clear: true })}>clear</Button>}
      </p>
      <div className="flex items-end gap-2">
        <label className="text-xs">designate
          <Select value={des} onChange={e => setDes(e.target.value)}>
            <option value="">choose…</option>
            {designatable.map(t => <option key={t.id} value={t.id}>{t.glyph} {t.id}</option>)}
          </Select>
        </label>
        <Button size="sm" disabled={busy || !des} onClick={() => onCommand({ verb: "designate", x: s.at.x, y: s.at.y, type: des })}>Designate</Button>
      </div>
      <div className="flex items-end gap-2">
        <label className="text-xs">threshold
          <Select value={thrCommodity} onChange={e => setThrCommodity(e.target.value)}>
            {view.commodityIds.map(c => <option key={c} value={c}>{c}</option>)}
          </Select>
        </label>
        <Input value={thrAmount} onChange={e => setThrAmount(e.target.value)} placeholder="amount" className="w-24" />
        <Button size="sm" disabled={busy || thrAmount === ""} onClick={() => onCommand({ verb: "threshold", x: s.at.x, y: s.at.y, commodity: thrCommodity, amount: Number(thrAmount) })}>Set</Button>
        {s.thresholds[thrCommodity] !== undefined && <Button size="sm" variant="ghost" disabled={busy} onClick={() => onCommand({ verb: "threshold", x: s.at.x, y: s.at.y, commodity: thrCommodity, clear: true })}>clear</Button>}
      </div>
    </div>
  );
}

function fmt(v: number | undefined): string { return v === undefined ? "0" : Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(1); }
