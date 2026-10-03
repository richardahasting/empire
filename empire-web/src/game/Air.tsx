import { useState } from "react";
import type { CommandRequest, CountryView, NukeClass, NukeView, PlaneView, Rules, SectorView } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

const rel = (c: { x: number; y: number }) => `${c.x},${c.y}`;

/**
 * What a plane's class can do that the view does not say outright (issue #71): "sweep" clears sea mines on
 * the way to a hex and back, "mine" lays them by dropping shells on the water.
 */
const planeFlags = (rules: Rules, cls: string) => rules.planes?.classes.find(c => c.id === cls)?.flags ?? [];

/** A sector relative to your capital, absolute — wrapping as the world does; undefined off the edge of one that does not. */
function absolute(view: CountryView, dx: number, dy: number): { x: number; y: number } | undefined {
  let x = view.capital.x + dx, y = view.capital.y + dy;
  if (view.wrapX) x = ((x % view.width) + view.width) % view.width; else if (x < 0 || x >= view.width) return undefined;
  if (view.wrapY) y = ((y % view.height) + view.height) % view.height; else if (y < 0 || y >= view.height) return undefined;
  return { x, y };
}

/**
 * Your planes (issue #262): where each sits, its condition, what it carries and how far it strikes; and its sorties.
 * A sortie takes petrol and bombs off the field it flies from, and whatever it flies against shoots back. Fighters of a
 * country at war with you rise against it on the way, and the escorts it takes fight them first (issue #71).
 */
export function Air({ view, rules, busy, onCommand }: { view: CountryView; rules: Rules; busy: boolean; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [dialog, setDialog] = useState<{ kind: "bomb" | "recon" | "defend" | "fly" | "drop" | "paradrop" | "launch" | "arm" | "sweep"; plane: PlaneView } | null>(null);
  const nukes = view.nukes ?? [];
  const storedWith = (p: PlaneView) => nukes.filter(n => n.plane === 0 && n.at.x === p.at.x && n.at.y === p.at.y && n.weight <= p.load);
  const planes = view.planes ?? [];
  const overhead = view.overhead ?? [];
  if (planes.length === 0 && overhead.length === 0 && nukes.length === 0) return <p className="text-xs text-muted-foreground">No planes. Designate an airfield, then right-click it and choose “Build plane…”.</p>;
  return (
    <div className="space-y-2 text-xs">
      {nukes.length > 0 && <div className="rounded-md border border-border p-2">
        <div className="font-medium">Warheads</div>
        {nukes.map(n => <div key={n.id}>#{n.id} {n.name} at {rel(n.relative)} · blast {n.blast}, {n.damage}% at ground zero · weighs {n.weight} · {n.plane ? `armed on plane #${n.plane}, ${n.airburst ? "airburst" : "groundburst"}` : "stored"}</div>)}
      </div>}
      {overhead.length > 0 && <div className="rounded-md border border-border p-2">
        <div className="font-medium">Satellites overhead</div>
        {overhead.map((o, i) => <div key={i}>{o.ownerName}’s {o.name} over {rel(o.relative)}</div>)}
      </div>}
      {planes.map(p => (
        <div key={p.id} className="rounded-md border border-border p-2">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <span className="font-mono">#{p.id}</span>
            <span className="font-medium">{p.name}</span>
            <span className="text-muted-foreground">
              at {rel(p.relative)} · {p.efficiency.toFixed(0)}% · {p.load > 0 ? `${p.load.toFixed(0)} bombs · ` : ""}accuracy {p.accuracy.toFixed(0)}% · strikes {Math.floor(p.reach)} hexes
              {p.missile ? "" : p.intercept ? " · fighter: rises against raids, can escort" : p.escort ? " · escort" : ""}{!p.missile && (p.intercept || p.escort) ? ` · attack ${p.attack.toFixed(1)}, defence ${p.defense.toFixed(1)}` : ""}
            </span>
          </div>
          {p.aboard !== 0 && <div>Aboard ship #{p.aboard}: it flies from her, on her petrol and shells.</div>}
          {p.opRelative && <div>Air defence within {p.radius} of {rel(p.opRelative)}: at war it rises over any sector there.</div>}
          {p.note && <div className="text-muted-foreground">{p.note}</div>}
          {p.efficiency < 80 && <div className="text-destructive">Shot up: it may turn back before it gets there. Leave it on the field to be fitted out.</div>}
          {p.missile && <div>{p.rises ? "A missile that rises by itself against what comes at you — never launched."
            : p.satellite ? "An anti-sat: launched once at an enemy satellite over a sector you see, and spent; it also rises against one put up over you."
            : "A missile: launched once, and spent."}</div>}
          {p.nuke !== 0 && <div className="text-destructive">Warhead #{p.nuke} aboard: it goes off where this {p.missile ? "missile comes down" : "plane bombs"}.</div>}
          {p.nukeCarrier && (p.nuke !== 0 || storedWith(p).length > 0) && <div className="mt-1 flex flex-wrap gap-1">
            {p.nuke === 0 && <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "arm", plane: p })}>Arm…</Button>}
            {p.nuke !== 0 && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "disarm", plane: p.id })}>Disarm</Button>}
          </div>}
          {p.satellite && !p.missile && <div>{p.orbit
            ? `${p.orbit === "geosync" ? "Geostationary" : "In orbit"} over ${rel(p.relative)}${p.ready ? "." : " — it reports from the next update."}`
            : "A satellite: launched into orbit over a sector, where it stays."}</div>}
          {p.satellite && !p.missile && <div className="mt-1">{p.orbit
            ? <Button size="sm" variant="secondary" disabled={busy || !p.ready} onClick={() => void onCommand({ verb: "satellite", plane: p.id })}>Report</Button>
            : <Button size="sm" variant="secondary" disabled={busy} onClick={() => setDialog({ kind: "launch", plane: p })}>Launch…</Button>}</div>}
          {p.missile && <div className="mt-1 flex flex-wrap gap-1">
            {!p.rises && <Button size="sm" variant="secondary" disabled={busy} onClick={() => setDialog({ kind: "launch", plane: p })}>Launch…</Button>}
            {p.intercept && <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "defend", plane: p })}>Air defence…</Button>}
            {p.opRelative && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "air_defence", plane: p.id, clear: true })}>Off air defence</Button>}
          </div>}
          {!p.missile && !p.satellite && <div className="mt-1 flex flex-wrap gap-1">
            {p.load > 0 && <Button size="sm" variant="secondary" disabled={busy} onClick={() => setDialog({ kind: "bomb", plane: p })}>Bomb…</Button>}
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "recon", plane: p })}>Reconnoitre…</Button>
            {p.intercept && <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "defend", plane: p })}>Air defence…</Button>}
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "fly", plane: p })}>Fly to…</Button>
            {p.cargo && <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "drop", plane: p })}>Drop supplies…</Button>}
            {p.para && <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "paradrop", plane: p })}>Paradrop…</Button>}
            {planeFlags(rules, p.cls).includes("sweep") && <Button size="sm" variant="ghost" disabled={busy} title="fly out over the water and back, clearing sea mines in every hex on the way" onClick={() => setDialog({ kind: "sweep", plane: p })}>Sweep mines…</Button>}
            {p.opRelative && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "air_defence", plane: p.id, clear: true })}>Off air defence</Button>}
          </div>}
        </div>
      ))}
      {dialog?.kind === "arm" && <ArmDialog plane={dialog.plane} choices={storedWith(dialog.plane)} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog?.kind === "launch" && <LaunchDialog plane={dialog.plane} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog?.kind === "defend" && <DefendDialog plane={dialog.plane} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog?.kind === "sweep" && <SweepDialog plane={dialog.plane} view={view} rules={rules} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog && (dialog.kind === "fly" || dialog.kind === "drop" || dialog.kind === "paradrop") && <TransportDialog kind={dialog.kind} plane={dialog.plane} view={view} rules={rules} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog && (dialog.kind === "bomb" || dialog.kind === "recon") && <SortieDialog kind={dialog.kind} plane={dialog.plane} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
    </div>
  );
}

function SortieDialog({ kind, plane, view, busy, onClose, onCommand }:
  { kind: "bomb" | "recon"; plane: PlaneView; view: CountryView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [to, setTo] = useState("");
  const [raid, setRaid] = useState(plane.tactical ? "pinpoint" : "strategic");
  const [escorts, setEscorts] = useState<number[]>([]);
  const canEscort = (view.planes ?? []).filter(p => p.id !== plane.id && !p.missile && !p.satellite && (p.intercept || p.escort));
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
 * Sweep sea mines from the air (issue #71; the original's sweep): the planes fly out over the water to x,y and
 * home again, and in each sea hex along the way each clears at most one mine. A less accurate plane sweeps
 * better — the chance is (100 − accuracy)/100 — because it is looking down, not aiming.
 */
function SweepDialog({ plane, view, rules, busy, onClose, onCommand }:
  { plane: PlaneView; view: CountryView; rules: Rules; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [to, setTo] = useState("");
  const [with_, setWith] = useState<number[]>([]);
  const [escorts, setEscorts] = useState<number[]>([]);
  const mates = (view.planes ?? []).filter(p => p.id !== plane.id && p.at.x === plane.at.x && p.at.y === plane.at.y && planeFlags(rules, p.cls).includes("sweep"));
  const canEscort = (view.planes ?? []).filter(p => p.id !== plane.id && !p.missile && !p.satellite && (p.intercept || p.escort));
  const [tx, ty] = to.split(",").map(s => Number(s.trim()));
  const target = Number.isFinite(tx) && Number.isFinite(ty) ? view.sectors.find(s => s.relative.x === tx && s.relative.y === ty) : undefined;
  const sea = !!target && target.terrain === "ocean";
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Sweep mines with plane #{plane.id} from {rel(plane.relative)}</DialogTitle>
          <DialogDescription>
            They fly out to the hex you name and back — up to {Math.floor(plane.reach)} hexes out — and sweep every sea hex on the way, each plane clearing at most one mine a hex. Nobody can see sea mines, so sweep the water you mean to sail through. At war, enemy fighters rise against them on the way.
          </DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label className="grid gap-1">Out to (x,y)<Input value={to} onChange={e => setTo(e.target.value)} placeholder="e.g. 6,2" autoFocus /></label>
          {mates.length > 0 && (
            <fieldset className="grid gap-1">
              <legend className="mb-1">With them — other sweepers on this field</legend>
              {mates.map(m => (
                <label key={m.id} className="flex items-center gap-2 text-xs">
                  <Checkbox checked={with_.includes(m.id)} onChange={() => setWith(w => w.includes(m.id) ? w.filter(x => x !== m.id) : [...w, m.id])} />
                  #{m.id} {m.name} · {m.efficiency.toFixed(0)}% · accuracy {m.accuracy.toFixed(0)}%
                </label>
              ))}
            </fieldset>
          )}
          {canEscort.length > 0 && (
            <fieldset className="grid gap-1">
              <legend className="mb-1">Escorts — fighters on fields within 4 hexes; they fight interceptors first</legend>
              {canEscort.map(e => (
                <label key={e.id} className="flex items-center gap-2 text-xs">
                  <Checkbox checked={escorts.includes(e.id)} onChange={() => setEscorts(s => s.includes(e.id) ? s.filter(x => x !== e.id) : [...s, e.id])} />
                  #{e.id} {e.name} at {rel(e.relative)} · {e.efficiency.toFixed(0)}%
                </label>
              ))}
            </fieldset>
          )}
        </div>
        {to && !sea && <p className="text-xs text-destructive">{target ? "Not open water: sea mines lie at sea, so sweep a hex of ocean." : "Nothing on your chart there."}</p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !sea} onClick={async () => {
            if (!target) return;
            await onCommand({ verb: "sweep", planes: [plane.id, ...with_], x: target.at.x, y: target.at.y, units: escorts });
            onClose();
          }}>Sweep</Button>
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
function TransportDialog({ kind, plane, view, rules, busy, onClose, onCommand }:
  { kind: "fly" | "drop" | "paradrop"; plane: PlaneView; view: CountryView; rules: Rules; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const field = view.sectors.find(s => s.at.x === plane.at.x && s.at.y === plane.at.y);
  const [with_, setWith] = useState<number[]>([]);
  const [to, setTo] = useState("");
  const [what, setWhat] = useState("");
  const goods = field ? Object.entries(field.stock).filter(([, q]) => q >= 1).map(([c]) => c) : [];
  const [tx, ty] = to.split(",").map(s => Number(s.trim()));
  const target = Number.isFinite(tx) && Number.isFinite(ty) ? view.sectors.find(s => s.relative.x === tx && s.relative.y === ty) : undefined;
  const mine = !!target && target.owner === view.countryId;
  const carrierThere = kind === "fly" && !!target && plane.light && view.ships.some(s => s.at.x === target.at.x && s.at.y === target.at.y);
  // Shells dropped on the sea go in as mines (issue #71): the server routes a drop of shell over water to minelaying,
  // so the target is open ocean rather than land of yours, and only planes that can mine may go.
  const layer = (p: PlaneView) => planeFlags(rules, p.cls).includes("mine");
  const mineDrop = kind === "drop" && what === "shell" && !!target && target.terrain === "ocean" && layer(plane);
  const mates = (view.planes ?? []).filter(p => p.id !== plane.id && !p.missile && !p.satellite && p.at.x === plane.at.x && p.at.y === plane.at.y
    && (kind === "fly" || (kind === "drop" ? (mineDrop ? layer(p) : p.cargo) : p.para)));
  const ok = !!target && (kind === "paradrop" ? !mine && target.terrain !== "ocean"
    : kind === "drop" ? !!what && (mineDrop || mine)
    : mine || carrierThere);
  const title = kind === "fly" ? "Fly" : kind === "drop" ? "Drop supplies" : "Paradrop";
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{title} from {rel(plane.relative)}</DialogTitle>
          <DialogDescription>
            {kind === "fly" ? "One way, to an airfield of yours or onto a carrier of yours there (light planes only); they stay there. Transports carry twice their load." : kind === "drop" ? `Onto land of yours${layer(plane) ? ", or shells onto open water, where they go in as mines" : ""}; the planes fly home.` : "The field's soldiers, onto a sector not yours (not mountains, a capital, a fortress or a wasteland); they fight for it."}
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
          {kind === "drop" && layer(plane) && <p className="text-xs text-muted-foreground">Pick <span className="font-mono">shell</span> and a hex of open water to lay mines instead: they carry twice their load, a shell a mine, and nobody can see them afterwards.</p>}
          {mates.length > 0 && (
            <fieldset className="grid gap-1">
              <legend className="mb-1">{mineDrop ? "With them — other minelayers on this field" : "With them"}</legend>
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
            // a mate ticked before the target became open water may no longer be able to come
            const along = with_.filter(id => mates.some(m => m.id === id));
            await onCommand({ verb: kind, planes: [plane.id, ...along], x: target.at.x, y: target.at.y, commodity: what || undefined });
            onClose();
          }}>{mineDrop ? "Lay mines" : title}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Launch a missile (issue #71; the original's launch): once, one way, and it is spent; an anti-ship missile goes at a ship. */
function LaunchDialog({ plane, view, busy, onClose, onCommand }: { plane: PlaneView; view: CountryView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [to, setTo] = useState("");
  const [geo, setGeo] = useState(false);
  const [tx, ty] = to.split(",").map(s => Number(s.trim()));
  const target = Number.isFinite(tx) && Number.isFinite(ty) ? view.sectors.find(s => s.relative.x === tx && s.relative.y === ty) : undefined;
  const orbiter = plane.satellite && !plane.missile, antiSat = plane.satellite && plane.missile;
  // a satellite may go up over land you have never seen: that is what it is for
  const over = orbiter && Number.isInteger(tx) && Number.isInteger(ty) ? absolute(view, tx, ty) : undefined;
  const ok = orbiter ? !!over : !!target && (antiSat
    ? (view.overhead ?? []).some(o => o.relative.x === tx && o.relative.y === ty)
    : plane.marine ? target.owner !== view.countryId : target.owner >= 0 && target.owner !== view.countryId);
  const what = orbiter ? `Into orbit over (x,y), up to ${Math.floor(plane.reach * 2)} hexes away`
    : antiSat ? "At the enemy satellite over (x,y)" : plane.marine ? "At an enemy ship you see (x,y)" : "At (x,y)";
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Launch {plane.name} #{plane.id}</DialogTitle>
          <DialogDescription>{orbiter
            ? "Once, into orbit, where it stays and reports from the next update. The booster may fail on the pad, it may go a sector astray, and the anti-sats of a country at war with you may meet it."
            : `Once, and it is spent. It flies ${Math.floor(plane.reach * 2)} hexes, one way; no fighter or flak can touch it, though it may fail on the pad${antiSat ? "" : " and the enemy's ABMs may meet it"}. At war only.`}</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label className="grid gap-1">{what}<Input value={to} onChange={e => setTo(e.target.value)} autoFocus /></label>
          {orbiter && <label className="flex items-center gap-2"><Checkbox checked={geo} onChange={() => setGeo(g => !g)} />Geostationary: hang over that one sector rather than circle the world</label>}
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={busy || !ok} onClick={async () => {
            const at = orbiter ? over : target?.at;
            if (at) await onCommand({ verb: "launch", plane: plane.id, x: at.x, y: at.y, type: orbiter && geo ? "geo" : undefined });
            onClose();
          }}>Launch</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Arm a plane with a warhead stored where it is (issue #71; the original's arm): it goes off where the plane bombs or the missile lands. */
function ArmDialog({ plane, choices, busy, onClose, onCommand }:
  { plane: PlaneView; choices: NukeView[]; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [nuke, setNuke] = useState(choices[0]?.id ?? 0);
  const [air, setAir] = useState(false);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Arm {plane.name} #{plane.id}</DialogTitle>
          <DialogDescription>A warhead stored where it is, no heavier than it carries. It goes off where this {plane.missile ? "missile comes down" : "plane bombs"}: a groundburst hits hardest at the centre, an airburst further out.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label className="grid gap-1">Warhead
            <Select value={String(nuke)} onChange={e => setNuke(Number(e.target.value))}>
              {choices.map(n => <option key={n.id} value={n.id}>#{n.id} {n.name} · blast {n.blast} · weighs {n.weight}</option>)}
            </Select>
          </label>
          <label className="flex items-center gap-2"><Checkbox checked={air} onChange={() => setAir(a => !a)} />Airburst</label>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={busy || !nuke} onClick={async () => { await onCommand({ verb: "arm", plane: plane.id, amount: nuke, type: air ? "airburst" : undefined }); onClose(); }}>Arm it</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Build a warhead in a nuclear plant (issue #71; the original's build nuke): whole, from the plant's materials and your cash. */
export function BuildNukeDialog({ view, rules, plant, busy, onClose, onCommand }:
  { view: CountryView; rules: Rules; plant: SectorView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const classes = rules.nukes?.classes ?? [];
  const canBuild = (c: NukeClass) => view.levels.tech >= c.techRequired;
  const [cls, setCls] = useState(classes.find(canBuild)?.id ?? classes[0]?.id ?? "");
  const c = classes.find(x => x.id === cls);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Build a warhead at {rel(plant.relative)}</DialogTitle>
          <DialogDescription>Built whole, at once, from the plant's materials and your cash; the plant must be at {rules.nukes?.plantMinEfficiency ?? 60}% or better. Arm it on a bomber or a missile that can carry its weight.</DialogDescription></DialogHeader>
        <label className="grid gap-1 text-sm">Class
          <Select value={cls} onChange={e => setCls(e.target.value)}>
            {classes.map(x => <option key={x.id} value={x.id} disabled={!canBuild(x)}>{x.name}{canBuild(x) ? "" : ` (tech ${x.techRequired})`}</option>)}
          </Select>
        </label>
        {c && <p className="text-xs text-muted-foreground">blast {c.blast} · {c.damage}% at ground zero · weighs {c.weight}{c.flags.includes("neutron") ? " · neutron" : ""} · {Object.entries(c.build).map(([k, v]) => k === "cash" ? `$${v}` : `${v} ${k}`).join(", ")}</p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={busy || !c || !canBuild(c)} onClick={async () => { await onCommand({ verb: "build_nuke", x: plant.at.x, y: plant.at.y, type: cls }); onClose(); }}>Build it</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
