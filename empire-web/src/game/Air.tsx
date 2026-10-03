import { useState } from "react";
import type { CommandRequest, CountryView, PlaneView, Rules, SectorView } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

const rel = (c: { x: number; y: number }) => `${c.x},${c.y}`;

/**
 * Your planes (issue #262): where each sits, its condition, what it carries and how far it strikes; and its sorties.
 * A sortie takes petrol and bombs off the field it flies from, and whatever it flies against shoots back. Fighters of a
 * country at war with you rise against it on the way, and the escorts it takes fight them first (issue #71).
 */
export function Air({ view, busy, onCommand }: { view: CountryView; busy: boolean; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [dialog, setDialog] = useState<{ kind: "bomb" | "recon" | "defend" | "fly" | "drop" | "paradrop"; plane: PlaneView } | null>(null);
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
              {p.intercept ? " · fighter: rises against raids, can escort" : p.escort ? " · escort" : ""}{p.intercept || p.escort ? ` · attack ${p.attack.toFixed(1)}, defence ${p.defense.toFixed(1)}` : ""}
            </span>
          </div>
          {p.opRelative && <div>Air defence within {p.radius} of {rel(p.opRelative)}: at war it rises over any sector there.</div>}
          {p.note && <div className="text-muted-foreground">{p.note}</div>}
          {p.efficiency < 80 && <div className="text-destructive">Shot up: it may turn back before it gets there. Leave it on the field to be fitted out.</div>}
          <div className="mt-1 flex flex-wrap gap-1">
            {p.load > 0 && <Button size="sm" variant="secondary" disabled={busy} onClick={() => setDialog({ kind: "bomb", plane: p })}>Bomb…</Button>}
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "recon", plane: p })}>Reconnoitre…</Button>
            {p.intercept && <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "defend", plane: p })}>Air defence…</Button>}
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "fly", plane: p })}>Fly to…</Button>
            {p.cargo && <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "drop", plane: p })}>Drop supplies…</Button>}
            {p.para && <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "paradrop", plane: p })}>Paradrop…</Button>}
            {p.opRelative && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "air_defence", plane: p.id, clear: true })}>Off air defence</Button>}
          </div>
        </div>
      ))}
      {dialog?.kind === "defend" && <DefendDialog plane={dialog.plane} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog && (dialog.kind === "fly" || dialog.kind === "drop" || dialog.kind === "paradrop") && <TransportDialog kind={dialog.kind} plane={dialog.plane} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog && (dialog.kind === "bomb" || dialog.kind === "recon") && <SortieDialog kind={dialog.kind} plane={dialog.plane} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
    </div>
  );
}

function SortieDialog({ kind, plane, view, busy, onClose, onCommand }:
  { kind: "bomb" | "recon"; plane: PlaneView; view: CountryView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [to, setTo] = useState("");
  const [raid, setRaid] = useState(plane.tactical ? "pinpoint" : "strategic");
  const [escorts, setEscorts] = useState<number[]>([]);
  const canEscort = (view.planes ?? []).filter(p => p.id !== plane.id && (p.intercept || p.escort));
  const toggle = (id: number) => setEscorts(e => e.includes(id) ? e.filter(x => x !== id) : [...e, id]);
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
            Guns over the target will fire at it, and at war the enemy's fighters rise against it on the way.
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
          {canEscort.length > 0 && (
            <fieldset className="grid gap-1">
              <legend className="mb-1">Escorts — fighters on fields within 4 hexes; they fight interceptors first</legend>
              {canEscort.map(e => (
                <label key={e.id} className="flex items-center gap-2 text-xs">
                  <Checkbox checked={escorts.includes(e.id)} onChange={() => toggle(e.id)} />
                  #{e.id} {e.name} at {rel(e.relative)} · {e.efficiency.toFixed(0)}%
                </label>
              ))}
            </fieldset>
          )}
        </div>
        {to && !ok && <p className="text-xs text-destructive">{kind === "bomb" ? "Not a land sector of somebody else's." : "Nothing on your chart there."}</p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !ok} onClick={async () => {
            if (!target) return;
            await onCommand(kind === "bomb"
              ? { verb: "bomb", plane: plane.id, x: target.at.x, y: target.at.y, type: raid, units: escorts }
              : { verb: "recon", plane: plane.id, x: target.at.x, y: target.at.y, units: escorts });
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
        {c && <p className="text-xs text-muted-foreground">{c.flags.includes("intercept") ? "fighter: rises against raids · " : c.flags.includes("escort") ? "escort only · " : ""}{c.load > 0 ? `${c.load} bombs · ` : ""}accuracy {c.accuracy}% · strikes {Math.floor(c.range / 2)} hexes · {c.fuel} petrol a sortie · now {cost}</p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !c || !canBuild(c)} onClick={async () => { await onCommand({ verb: "build_plane", x: field.at.x, y: field.at.y, type: cls }); onClose(); }}>Build it</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Air defence (issue #71; the original's mission … a): a fighter guards the sectors within a radius of an op point —
 * anybody's, not only yours — and rises against raids there at war. The op point and radius are at most as far as it strikes.
 */
function DefendDialog({ plane, view, busy, onClose, onCommand }: { plane: PlaneView; view: CountryView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [op, setOp] = useState(rel(plane.relative));
  const [radius, setRadius] = useState(String(Math.floor(plane.reach)));
  const [ox, oy] = op.split(",").map(s => Number(s.trim()));
  const at = Number.isFinite(ox) && Number.isFinite(oy) ? view.sectors.find(s => s.relative.x === ox && s.relative.y === oy) : undefined;
  const r = Number(radius);
  const ok = !!at && Number.isInteger(r) && r >= 0;
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Air defence with plane #{plane.id}</DialogTitle>
          <DialogDescription>At war it rises against raids over any sector within the radius of the point it guards, not only over your own land. It reaches {Math.floor(plane.reach)} hexes from its field at {rel(plane.relative)}.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label className="grid gap-1">Guard around (x,y)<Input value={op} onChange={e => setOp(e.target.value)} autoFocus /></label>
          <label className="grid gap-1">Radius (0: as far as it reaches)<Input value={radius} onChange={e => setRadius(e.target.value)} inputMode="numeric" /></label>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !ok} onClick={async () => { if (!at) return; await onCommand({ verb: "air_defence", plane: plane.id, x: at.at.x, y: at.at.y, amount: r }); onClose(); }}>Guard it</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Air transport (issue #71; the original's fly, drop and paradrop). Fly: one way to an airfield of yours, transports carrying
 * their load twice over. Drop: supplies on a sector of yours, and home. Paradrop: the field's soldiers on a sector not yours.
 * Other transports on the same field may go too; at war, enemy fighters rise on the way.
 */
function TransportDialog({ kind, plane, view, busy, onClose, onCommand }:
  { kind: "fly" | "drop" | "paradrop"; plane: PlaneView; view: CountryView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const field = view.sectors.find(s => s.at.x === plane.at.x && s.at.y === plane.at.y);
  const mates = (view.planes ?? []).filter(p => p.id !== plane.id && p.at.x === plane.at.x && p.at.y === plane.at.y && (kind === "fly" || (kind === "drop" ? p.cargo : p.para)));
  const [with_, setWith] = useState<number[]>([]);
  const [to, setTo] = useState("");
  const [what, setWhat] = useState("");
  const goods = field ? Object.entries(field.stock).filter(([, q]) => q >= 1).map(([c]) => c) : [];
  const [tx, ty] = to.split(",").map(s => Number(s.trim()));
  const target = Number.isFinite(tx) && Number.isFinite(ty) ? view.sectors.find(s => s.relative.x === tx && s.relative.y === ty) : undefined;
  const mine = !!target && target.owner === view.countryId;
  const ok = !!target && (kind === "paradrop" ? !mine && target.terrain !== "ocean" : mine) && (kind !== "drop" || !!what);
  const title = kind === "fly" ? "Fly" : kind === "drop" ? "Drop supplies" : "Paradrop";
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{title} from {rel(plane.relative)}</DialogTitle>
          <DialogDescription>
            {kind === "fly" ? "One way, to an airfield of yours; they stay there. Transports carry twice their load." : kind === "drop" ? "Onto land of yours; the planes fly home." : "The field's soldiers, onto a sector not yours (not mountains, a capital, a fortress or a wasteland); they fight for it."}
            {" "}At war, enemy fighters rise on the way, and what a plane that is shot down or turns back carried is lost.
          </DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label className="grid gap-1">{kind === "fly" ? "To airfield (x,y)" : "At (x,y)"}<Input value={to} onChange={e => setTo(e.target.value)} autoFocus /></label>
          {kind !== "paradrop" && (
            <label className="grid gap-1">{kind === "fly" ? "Carry (optional)" : "Drop"}
              <Select value={what} onChange={e => setWhat(e.target.value)}>
                <option value="">{kind === "fly" ? "nothing" : "—"}</option>
                {goods.map(c => <option key={c} value={c}>{c} ({Math.floor(field?.stock[c] ?? 0)} on the field)</option>)}
              </Select>
            </label>
          )}
          {mates.length > 0 && (
            <fieldset className="grid gap-1">
              <legend className="mb-1">With them</legend>
              {mates.map(m => (
                <label key={m.id} className="flex items-center gap-2 text-xs">
                  <Checkbox checked={with_.includes(m.id)} onChange={() => setWith(w => w.includes(m.id) ? w.filter(x => x !== m.id) : [...w, m.id])} />
                  #{m.id} {m.name} · {m.efficiency.toFixed(0)}%
                </label>
              ))}
            </fieldset>
          )}
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !ok} onClick={async () => {
            if (!target) return;
            await onCommand({ verb: kind, planes: [plane.id, ...with_], x: target.at.x, y: target.at.y, commodity: what || undefined });
            onClose();
          }}>{title}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
