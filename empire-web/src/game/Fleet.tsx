import { useState } from "react";
import type { CommandRequest, CountryView, Rules, ShipView } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

interface Props {
  view: CountryView; rules: Rules; busy: boolean;
  onCommand: (c: CommandRequest) => Promise<void>;
  /** Start picking a destination on the map for this ship. */
  onSail: (ship: ShipView) => void;
}

const rel = (c: { x: number; y: number } | null | undefined) => c ? `${c.x},${c.y}` : "?";
export const shipGlyph = (rules: Rules, cls: string) => rules.ships?.classes.find(c => c.id === cls)?.glyph ?? "?";
const className = (rules: Rules, cls: string) => rules.ships?.classes.find(c => c.id === cls)?.name ?? cls;

/** Your fleet (issue #56): every ship, where it is, what it carries, where it is going, what it did last update; and the orders. */
export function Fleet({ view, rules, busy, onCommand, onSail }: Props) {
  const [dialog, setDialog] = useState<{ kind: "load" | "unload" | "lane"; ship: ShipView } | null>(null);
  const [pendingScrap, setPendingScrap] = useState<number | null>(null);
  const harbors = view.sectors.filter(s => s.full && (rules.sectorTypes.find(t => t.id === s.designation)?.flags ?? []).includes("builds_ships"));
  if (view.ships.length === 0) return <p className="text-xs text-muted-foreground">No ships. Right-click a harbour and choose “Build ship…”.{harbors.length === 0 ? " You have no harbour yet: designate a coastal sector as one." : ""}</p>;
  return (
    <div className="space-y-2 text-xs">
      {view.ships.map(s => {
        const going = s.lane ? `lane ${rel(s.lane.fromRelative)} ${s.lane.outbound ? "→" : "←"} ${rel(s.lane.toRelative)}${s.lane.cargo.length ? ` (${s.lane.cargo.join(", ")})` : ""}` : s.destRelative ? `to ${rel(s.destRelative)}` : s.docked ? "in harbour" : "holding";
        const cargo = Object.entries(s.stock).map(([c, q]) => `${Math.floor(q)} ${c}`).join(", ");
        return (
          <div key={s.id} className="rounded-md border border-border p-2">
            <div className="flex flex-wrap items-baseline gap-x-2">
              <span className="font-mono">#{s.id}</span>
              <span className="font-medium">{className(rules, s.cls)}{s.name ? ` “${s.name}”` : ""}</span>
              <span className="text-muted-foreground">at {rel(s.relative)} · {s.efficiency.toFixed(0)}% · {Math.floor(s.load)}/{s.hold}{cargo ? ` (${cargo})` : ""}</span>
            </div>
            <div className="text-muted-foreground">{going}{s.note ? ` · ${s.note}` : ""}</div>
            <div className="mt-1 flex flex-wrap gap-1">
              <Button size="sm" variant="secondary" disabled={busy || !!s.lane} onClick={() => onSail(s)}>Sail…</Button>
              {s.dest && !s.lane && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "sail", ship: s.id, clear: true })}>Hold</Button>}
              <Button size="sm" variant="ghost" disabled={busy || !s.docked} onClick={() => setDialog({ kind: "load", ship: s })}>Load…</Button>
              <Button size="sm" variant="ghost" disabled={busy || !s.docked || s.load <= 0} onClick={() => setDialog({ kind: "unload", ship: s })}>Unload…</Button>
              <Button size="sm" variant="ghost" disabled={busy || harbors.length < 2} onClick={() => setDialog({ kind: "lane", ship: s })}>{s.lane ? "Change lane…" : "Lane…"}</Button>
              {s.lane && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "lane", ship: s.id, clear: true })}>Leave lane</Button>}
              {s.docked && (pendingScrap === s.id
                ? <Button size="sm" variant="danger" disabled={busy} onClick={() => { setPendingScrap(null); void onCommand({ verb: "scrap", ship: s.id }); }}>Confirm scrap</Button>
                : <Button size="sm" variant="ghost" disabled={busy} onClick={() => setPendingScrap(s.id)}>Scrap</Button>)}
            </div>
          </div>
        );
      })}
      {dialog && (dialog.kind === "lane"
        ? <LaneDialog ship={dialog.ship} harbors={harbors} view={view} rules={rules} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />
        : <CargoDialog kind={dialog.kind} ship={dialog.ship} view={view} rules={rules} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />)}
    </div>
  );
}

function carries(rules: Rules, cls: string, c: string, isPerson: boolean): boolean {
  const list = rules.ships?.classes.find(x => x.id === cls)?.carries ?? [];
  return list.some(k => k === "all" || (k === "goods" && !isPerson) || (k === "people" && isPerson) || k === c);
}

function CargoDialog({ kind, ship, view, rules, busy, onClose, onCommand }: { kind: "load" | "unload"; ship: ShipView; view: CountryView; rules: Rules; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const here = view.sectors.find(s => s.at.x === ship.at.x && s.at.y === ship.at.y);
  const people = new Set(rules.commodities.filter(c => c.isPerson).map(c => c.id));
  const choices = kind === "load"
    ? view.commodityIds.filter(c => carries(rules, ship.cls, c, people.has(c)) && (here?.stock[c] ?? 0) >= 1)
    : Object.keys(ship.stock);
  const [commodity, setCommodity] = useState(choices[0] ?? "");
  const room = ship.hold - ship.load;
  const max = kind === "load" ? Math.min(room, here?.stock[commodity] ?? 0) : (ship.stock[commodity] ?? 0);
  const [qty, setQty] = useState(String(Math.floor(max)));
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{kind === "load" ? "Load" : "Unload"} ship #{ship.id} at {rel(ship.relative)}</DialogTitle><DialogDescription>{kind === "load" ? `Hold: ${Math.floor(ship.load)} of ${ship.hold} used.` : "Into the harbour, up to its capacity."}</DialogDescription></DialogHeader>
        {choices.length === 0 ? <p className="text-sm text-muted-foreground">{kind === "load" ? "Nothing here this ship can carry." : "The hold is empty."}</p> : (
          <div className="grid gap-3 text-sm">
            <label>Commodity
              <Select value={commodity} onChange={e => { setCommodity(e.target.value); const m = kind === "load" ? Math.min(room, here?.stock[e.target.value] ?? 0) : (ship.stock[e.target.value] ?? 0); setQty(String(Math.floor(m))); }}>
                {choices.map(c => <option key={c} value={c}>{c} ({Math.floor(kind === "load" ? here?.stock[c] ?? 0 : ship.stock[c] ?? 0)})</option>)}
              </Select>
            </label>
            <label>Quantity<Input value={qty} onChange={e => setQty(e.target.value)} inputMode="numeric" autoFocus /></label>
          </div>
        )}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !commodity || !(Number(qty) > 0)} onClick={async () => { await onCommand({ verb: kind, ship: ship.id, commodity, amount: Number(qty) }); onClose(); }}>{kind === "load" ? "Load" : "Unload"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function LaneDialog({ ship, harbors, view, rules, busy, onClose, onCommand }: { ship: ShipView; harbors: CountryView["sectors"]; view: CountryView; rules: Rules; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const key = (s: { at: { x: number; y: number } }) => `${s.at.x},${s.at.y}`;
  const [from, setFrom] = useState(ship.lane ? `${ship.lane.from.x},${ship.lane.from.y}` : key(harbors[0]));
  const [to, setTo] = useState(ship.lane ? `${ship.lane.to.x},${ship.lane.to.y}` : key(harbors[1] ?? harbors[0]));
  const people = new Set(rules.commodities.filter(c => c.isPerson).map(c => c.id));
  const carriable = view.commodityIds.filter(c => carries(rules, ship.cls, c, people.has(c)));
  const [cargo, setCargo] = useState<string[]>(ship.lane?.cargo ?? []);
  const parse = (k: string) => { const [x, y] = k.split(",").map(Number); return { x, y }; };
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Shipping lane for ship #{ship.id}</DialogTitle><DialogDescription>A standing order: at the first harbour it loads whatever is above the harbour's thresholds, sails to the second, unloads everything, and comes back — every update, until you clear it.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label>Load at<Select value={from} onChange={e => setFrom(e.target.value)}>{harbors.map(h => <option key={key(h)} value={key(h)}>{rel(h.relative)} · {h.designation}</option>)}</Select></label>
          <label>Unload at<Select value={to} onChange={e => setTo(e.target.value)}>{harbors.map(h => <option key={key(h)} value={key(h)}>{rel(h.relative)} · {h.designation}</option>)}</Select></label>
          <div>Carry <span className="text-muted-foreground">(none ticked = anything it can)</span>
            <div className="mt-1 flex flex-wrap gap-2">
              {carriable.map(c => <label key={c} className="flex items-center gap-1"><input type="checkbox" checked={cargo.includes(c)} onChange={e => setCargo(cs => e.target.checked ? [...cs, c] : cs.filter(x => x !== c))} />{c}</label>)}
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || from === to} onClick={async () => { const f = parse(from), t = parse(to); await onCommand({ verb: "lane", ship: ship.id, x: f.x, y: f.y, x2: t.x, y2: t.y, cargo }); onClose(); }}>Set lane</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Lay a hull in this harbour. */
export function BuildShipDialog({ view, rules, harbor, busy, onClose, onCommand }: { view: CountryView; rules: Rules; harbor: CountryView["sectors"][number]; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const classes = rules.ships?.classes ?? [];
  const canBuild = (c: (typeof classes)[number]) => view.levels.tech >= c.techRequired;
  const [cls, setCls] = useState(classes.find(canBuild)?.id ?? classes[0]?.id ?? "");
  const [name, setName] = useState("");
  const c = classes.find(x => x.id === cls);
  const cost = c ? Object.entries(c.build ?? {}).map(([k, v]) => `${v} ${k}`).join(", ") : "";
  const short = c ? Object.entries(c.build ?? {}).filter(([k, v]) => k !== "cash" && (harbor.stock[k] ?? 0) < v).map(([k, v]) => `${k} (${Math.floor(harbor.stock[k] ?? 0)} of ${v})`) : [];
  const minEff = rules.ships?.harborMinEfficiency ?? 60;
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Build a ship at {rel(harbor.relative)}</DialogTitle><DialogDescription>Materials come from this harbour's stock and cash from the treasury, now. The hull appears at {rules.ships?.startEfficiency ?? 20}% and fits out while docked here, {rules.ships?.dockPointsPerUpdate ?? 20} points an update.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label>Class
            <Select value={cls} onChange={e => setCls(e.target.value)}>
              {classes.map(x => <option key={x.id} value={x.id} disabled={!canBuild(x)}>{x.glyph} {x.name}{canBuild(x) ? "" : ` (tech ${x.techRequired})`}</option>)}
            </Select>
          </label>
          {c && <p className="text-xs text-muted-foreground">{c.role} · hold {c.hold} · speed {c.speed} hexes/update at 100% · costs {cost}{c.fishingRate ? ` · fishes ×${c.fishingRate}` : ""}{c.happinessPerEtu ? ` · happiness at sea` : ""}</p>}
          {short.length > 0 && <p className="text-xs text-destructive">Harbour short of {short.join(", ")}.</p>}
          {harbor.efficiency < minEff && <p className="text-xs text-destructive">The harbour is at {harbor.efficiency.toFixed(0)}%; it needs {minEff}% to lay a hull.</p>}
          <label>Name (optional)<Input value={name} onChange={e => setName(e.target.value)} maxLength={30} /></label>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !c || !canBuild(c) || short.length > 0 || harbor.efficiency < minEff} onClick={async () => { await onCommand({ verb: "build_ship", x: harbor.at.x, y: harbor.at.y, type: cls, name: name || undefined }); onClose(); }}>Lay the hull</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
