import { useState } from "react";
import type { CommandRequest, CountryView, Rules, SectorView, UnitView } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

const rel = (c: { x: number; y: number }) => `${c.x},${c.y}`;

/**
 * Your land units (issue #247): where each stands, how fit, soldiers and supplies, its own mobility, and what it is worth in
 * a fight; and its orders — march, take on or put down soldiers and supplies. Attacks are given from the enemy sector's menu.
 */
export function Army({ view, busy, onCommand }: { view: CountryView; busy: boolean; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [dialog, setDialog] = useState<{ kind: "march" | "load" | "unload"; unit: UnitView } | null>(null);
  const units = view.units ?? [];
  if (units.length === 0) return <p className="text-xs text-muted-foreground">No land units. Designate a headquarters, then right-click it and choose “Build unit…”.</p>;
  return (
    <div className="space-y-2 text-xs">
      {units.map(u => (
        <div key={u.id} className="rounded-md border border-border p-2">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <span className="font-mono">#{u.id}</span>
            <span className="font-medium">{u.name}</span>
            <span className="text-muted-foreground">at {rel(u.relative)} · {u.efficiency.toFixed(0)}% · mob {u.mobility.toFixed(0)} · att {u.attack.toFixed(0)} · def {u.defense.toFixed(0)}</span>
          </div>
          <div className="text-muted-foreground">
            {Object.keys(u.carries).map(c => `${c} ${Math.floor(u.stock[c] ?? 0)}/${u.carries[c]}`).join(" · ")}{u.note ? ` · ${u.note}` : ""}
          </div>
          {(u.stock["mil"] ?? 0) < 1 && <div className="text-destructive">No soldiers: it cannot fight or defend. Load mil in a sector that has them.</div>}
          <div className="mt-1 flex flex-wrap gap-1">
            <Button size="sm" variant="secondary" disabled={busy || u.mobility < 1} onClick={() => setDialog({ kind: "march", unit: u })}>March…</Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "load", unit: u })}>Load…</Button>
            <Button size="sm" variant="ghost" disabled={busy || Object.keys(u.stock).length === 0} onClick={() => setDialog({ kind: "unload", unit: u })}>Unload…</Button>
          </div>
        </div>
      ))}
      {dialog?.kind === "march" && <MarchDialog unit={dialog.unit} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog && dialog.kind !== "march" && <UnitCargoDialog kind={dialog.kind} unit={dialog.unit} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
    </div>
  );
}

function MarchDialog({ unit, view, busy, onClose, onCommand }: { unit: UnitView; view: CountryView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [to, setTo] = useState("");
  const [tx, ty] = to.split(",").map(s => Number(s.trim()));
  const target = Number.isFinite(tx) && Number.isFinite(ty) ? view.sectors.find(s => s.relative.x === tx && s.relative.y === ty) : undefined;
  const ok = !!target && target.full && target.terrain !== "ocean";
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>March unit #{unit.id} from {rel(unit.relative)}</DialogTitle>
          <DialogDescription>Through your own land, the cheapest way, as far as its {unit.mobility.toFixed(0)} mobility carries it; the rest waits for more mobility. To take enemy land, attack it from the enemy sector's menu.</DialogDescription></DialogHeader>
        <label className="grid gap-1 text-sm">To (x,y)<Input value={to} onChange={e => setTo(e.target.value)} placeholder="e.g. 3,-2" autoFocus /></label>
        {to && !ok && <p className="text-xs text-destructive">{target ? "Not a sector of yours on land." : "No sector of yours there."}</p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !ok} onClick={async () => { if (!target) return; await onCommand({ verb: "march", unit: unit.id, x: target.at.x, y: target.at.y }); onClose(); }}>March</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function UnitCargoDialog({ kind, unit, view, busy, onClose, onCommand }: { kind: "load" | "unload"; unit: UnitView; view: CountryView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const here = view.sectors.find(s => s.at.x === unit.at.x && s.at.y === unit.at.y);
  const choices = kind === "load" ? Object.keys(unit.carries) : Object.keys(unit.stock);
  const [commodity, setCommodity] = useState(choices[0] ?? "mil");
  const room = Math.max(0, (unit.carries[commodity] ?? 0) - (unit.stock[commodity] ?? 0));
  const max = kind === "load" ? Math.min(room, Math.floor(here?.stock[commodity] ?? 0)) : Math.floor(unit.stock[commodity] ?? 0);
  const [amount, setAmount] = useState(String(max));
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{kind === "load" ? "Load" : "Unload"} unit #{unit.id} at {rel(unit.relative)}</DialogTitle>
          <DialogDescription>{kind === "load" ? "It takes on what this sector has, up to what the unit carries." : "It puts down into this sector."}</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label>Commodity
            <Select value={commodity} onChange={e => setCommodity(e.target.value)}>
              {choices.map(c => <option key={c} value={c}>{c} (aboard {Math.floor(unit.stock[c] ?? 0)}{kind === "load" ? `, room ${Math.max(0, (unit.carries[c] ?? 0) - (unit.stock[c] ?? 0))}, here ${Math.floor(here?.stock[c] ?? 0)}` : ""})</option>)}
            </Select>
          </label>
          <label>Amount<Input value={amount} onChange={e => setAmount(e.target.value)} inputMode="numeric" /></label>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !(Number(amount) >= 1)} onClick={async () => { await onCommand({ verb: kind === "load" ? "lload" : "lunload", unit: unit.id, commodity, amount: Number(amount) }); onClose(); }}>{kind === "load" ? "Load" : "Unload"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Raise a land unit in a headquarters (issue #247): laid down at a tenth of its materials and cost, it builds up there. */
export function BuildUnitDialog({ view, rules, hq, busy, onClose, onCommand }: { view: CountryView; rules: Rules; hq: SectorView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const classes = rules.land?.classes ?? [];
  const start = rules.land?.startEfficiency ?? 10;
  const canBuild = (c: (typeof classes)[number]) => view.levels.tech >= c.techRequired;
  const [cls, setCls] = useState(classes.find(canBuild)?.id ?? classes[0]?.id ?? "");
  const c = classes.find(x => x.id === cls);
  const cost = c ? Object.entries(c.build).map(([k, v]) => `${Math.ceil(v * start / 100)} ${k}`).join(", ") : "";
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Build a unit at {rel(hq.relative)}</DialogTitle>
          <DialogDescription>It is raised at {start}% for that share of its materials and cost, and builds up while it stands here. Then load soldiers into it.</DialogDescription></DialogHeader>
        <label className="grid gap-1 text-sm">Class
          <Select value={cls} onChange={e => setCls(e.target.value)}>
            {classes.map(x => <option key={x.id} value={x.id} disabled={!canBuild(x)}>{x.glyph} {x.name}{canBuild(x) ? "" : ` (tech ${x.techRequired})`}</option>)}
          </Select>
        </label>
        {c && <p className="text-xs text-muted-foreground">attack {c.attack} · defence {c.defense} · speed {c.speed} · carries {Object.entries(c.carries).map(([k, v]) => `${v} ${k}`).join(", ")} · now {cost}</p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !c || !canBuild(c)} onClick={async () => { await onCommand({ verb: "build_unit", x: hq.at.x, y: hq.at.y, type: cls }); onClose(); }}>Raise it</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
