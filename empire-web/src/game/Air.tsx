import { useState } from "react";
import type { CommandRequest, CountryView, PlaneView, Rules, SectorView } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

const rel = (c: { x: number; y: number }) => `${c.x},${c.y}`;

/**
 * Your planes (issue #262): where each sits, its condition, what it carries and how far it strikes; and its sorties.
 * A sortie takes petrol and bombs off the field it flies from, and whatever it flies against shoots back.
 */
export function Air({ view, busy, onCommand }: { view: CountryView; busy: boolean; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [dialog, setDialog] = useState<{ kind: "bomb" | "recon"; plane: PlaneView } | null>(null);
  const planes = view.planes ?? [];
  if (planes.length === 0) return <p className="text-xs text-muted-foreground">No planes. Designate an airfield, then right-click it and choose “Build plane…”.</p>;
  return (
    <div className="space-y-2 text-xs">
      {planes.map(p => (
        <div key={p.id} className="rounded-md border border-border p-2">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <span className="font-mono">#{p.id}</span>
            <span className="font-medium">{p.name}</span>
            <span className="text-muted-foreground">
              at {rel(p.relative)} · {p.efficiency.toFixed(0)}% · {p.load > 0 ? `${p.load.toFixed(0)} bombs · ` : ""}accuracy {p.accuracy.toFixed(0)}% · strikes {Math.floor(p.reach)} hexes
            </span>
          </div>
          {p.note && <div className="text-muted-foreground">{p.note}</div>}
          {p.efficiency < 80 && <div className="text-destructive">Shot up: it may turn back before it gets there. Leave it on the field to be fitted out.</div>}
          <div className="mt-1 flex flex-wrap gap-1">
            {p.load > 0 && <Button size="sm" variant="secondary" disabled={busy} onClick={() => setDialog({ kind: "bomb", plane: p })}>Bomb…</Button>}
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "recon", plane: p })}>Reconnoitre…</Button>
          </div>
        </div>
      ))}
      {dialog && <SortieDialog kind={dialog.kind} plane={dialog.plane} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
    </div>
  );
}

function SortieDialog({ kind, plane, view, busy, onClose, onCommand }:
  { kind: "bomb" | "recon"; plane: PlaneView; view: CountryView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [to, setTo] = useState("");
  const [raid, setRaid] = useState(plane.tactical ? "pinpoint" : "strategic");
  const [tx, ty] = to.split(",").map(s => Number(s.trim()));
  const target = Number.isFinite(tx) && Number.isFinite(ty) ? view.sectors.find(s => s.relative.x === tx && s.relative.y === ty) : undefined;
  const theirs = !!target && target.owner >= 0 && target.owner !== view.countryId && target.terrain !== "ocean";
  const ok = kind === "bomb" ? theirs : !!target;
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{kind === "bomb" ? "Bomb" : "Reconnoitre"} with plane #{plane.id} from {rel(plane.relative)}</DialogTitle>
          <DialogDescription>
            It strikes {Math.floor(plane.reach)} hexes out and comes home the same turn, taking its petrol{kind === "bomb" ? " and bombs" : ""} off the field.
            Guns over the target will fire at it.
          </DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label className="grid gap-1">At (x,y)<Input value={to} onChange={e => setTo(e.target.value)} placeholder="e.g. 4,-1" autoFocus /></label>
          {kind === "bomb" && (
            <label className="grid gap-1">Raid
              <Select value={raid} onChange={e => setRaid(e.target.value)}>
                <option value="strategic">Strategic — the sector itself{plane.tactical ? "" : " (what this plane is built for)"}</option>
                <option value="pinpoint">Pinpoint — what is in it{plane.tactical ? " (what this plane is built for)" : ""}</option>
              </Select>
            </label>
          )}
        </div>
        {to && !ok && <p className="text-xs text-destructive">{kind === "bomb" ? "Not a land sector of somebody else's." : "Nothing on your chart there."}</p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !ok} onClick={async () => {
            if (!target) return;
            await onCommand(kind === "bomb"
              ? { verb: "bomb", plane: plane.id, x: target.at.x, y: target.at.y, type: raid }
              : { verb: "recon", plane: plane.id, x: target.at.x, y: target.at.y });
            onClose();
          }}>{kind === "bomb" ? "Fly the raid" : "Fly over"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Lay down a plane on an airfield (issue #262): a tenth of its materials and cash now, fitted out on the field. */
export function BuildPlaneDialog({ view, rules, field, busy, onClose, onCommand }:
  { view: CountryView; rules: Rules; field: SectorView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const classes = rules.planes?.classes ?? [];
  const start = rules.planes?.startEfficiency ?? 10;
  const canBuild = (c: (typeof classes)[number]) => view.levels.tech >= c.techRequired;
  const [cls, setCls] = useState(classes.find(canBuild)?.id ?? classes[0]?.id ?? "");
  const c = classes.find(x => x.id === cls);
  const cost = c ? Object.entries(c.build).map(([k, v]) => `${Math.ceil(v * start / 100)} ${k}`).join(", ") : "";
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Build a plane at {rel(field.relative)}</DialogTitle>
          <DialogDescription>It is laid down at {start}% for that share of its materials and cash, and fits out on the field. Keep petrol and shells there for it to fly with.</DialogDescription></DialogHeader>
        <label className="grid gap-1 text-sm">Class
          <Select value={cls} onChange={e => setCls(e.target.value)}>
            {classes.map(x => <option key={x.id} value={x.id} disabled={!canBuild(x)}>{x.glyph} {x.name}{canBuild(x) ? "" : ` (tech ${x.techRequired})`}</option>)}
          </Select>
        </label>
        {c && <p className="text-xs text-muted-foreground">{c.load > 0 ? `${c.load} bombs · ` : ""}accuracy {c.accuracy}% · strikes {Math.floor(c.range / 2)} hexes · {c.fuel} petrol a sortie · now {cost}</p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !c || !canBuild(c)} onClick={async () => { await onCommand({ verb: "build_plane", x: field.at.x, y: field.at.y, type: cls }); onClose(); }}>Build it</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
