import { useEffect, useState } from "react";
import { shipHistory, type CommandRequest, type CountryView, type Rules, type ShipLogEntry, type ShipView } from "@/api/client";
import { bearing, neighbour } from "./bearing";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

interface Props {
  gameId: number;
  view: CountryView; rules: Rules; busy: boolean;
  onCommand: (c: CommandRequest) => Promise<void>;
  /** Start picking a destination on the map for this ship. */
  onSail: (ship: ShipView) => void;
}

const rel = (c: { x: number; y: number } | null | undefined) => c ? `${c.x},${c.y}` : "?";
const isTender = (rules: Rules, cls: string) => rules.ships?.classes.find(c => c.id === cls)?.role === "tender";
/** Warship missions (issue #68); console verbs take patrol, blockade and interdict with points on the map. */
const MILITARY = ["patrol", "search", "escort", "blockade", "interdict"];
export const shipGlyph = (rules: Rules, cls: string) => rules.ships?.classes.find(c => c.id === cls)?.glyph ?? "?";
const className = (rules: Rules, cls: string) => rules.ships?.classes.find(c => c.id === cls)?.name ?? cls;

/** Your fleet (issue #56): every ship, where it is, what it carries, where it is going, what it did last update; and the orders. */
export function Fleet({ gameId, view, rules, busy, onCommand, onSail }: Props) {
  const [logbook, setLogbook] = useState<number | null>(null);
  const [dialog, setDialog] = useState<{ kind: "load" | "unload" | "lane" | "fire" | "escort" | "land"; ship: ShipView } | null>(null);
  const [pendingScrap, setPendingScrap] = useState<number | null>(null);
  const harbors = view.sectors.filter(s => s.full && (rules.sectorTypes.find(t => t.id === s.designation)?.flags ?? []).includes("builds_ships"));
  if (view.ships.length === 0) return <p className="text-xs text-muted-foreground">No ships. Right-click a harbour and choose “Build ship…”.{harbors.length === 0 ? " You have no harbour yet: designate a coastal sector as one." : ""}</p>;
  return (
    <div className="space-y-2 text-xs">
      {view.ships.map(s => {
        const going = s.lane ? `lane ${rel(s.lane.fromRelative)} ${s.lane.outbound ? "→" : "←"} ${rel(s.lane.toRelative)}${s.lane.cargo.length ? ` (${s.lane.cargo.join(", ")})` : ""}` : s.mission === "fish" || s.mission === "mine" ? `${s.mission === "fish" ? "fishing" : "mining"} from ${rel(s.homeRelative)}${s.destRelative ? ` → ${rel(s.destRelative)}` : ""}` : s.mission === "rescue" ? `answering a distress call from ship #${s.ward}${s.destRelative ? ` → ${rel(s.destRelative)}` : ""}` : s.mission && MILITARY.includes(s.mission) ? `${s.mission}${s.ward ? ` ship #${s.ward}` : s.routeRelative.length ? ` ${s.routeRelative.map(rel).join(" → ")}` : ""}${s.destRelative ? ` · bound for ${rel(s.destRelative)}` : ""}` : s.mission === "supply" ? `supply${s.destRelative ? ` → ${rel(s.destRelative)}` : ""}, refits at ${rel(s.homeRelative)}` : isTender(rules, s.cls) && !s.mission && !s.lane ? `on call for distress calls${s.destRelative ? ` · making for ${rel(s.destRelative)}` : s.docked ? " · waiting in harbour" : ""}` : s.destRelative ? `to ${rel(s.destRelative)}` : s.docked ? "in harbour" : "holding";
        const fisher = !!rules.ships?.classes.find(c => c.id === s.cls)?.fishingRate;
        const miner = !!rules.ships?.classes.find(c => c.id === s.cls)?.miningRate;
        const cls = rules.ships?.classes.find(c => c.id === s.cls);
        const armed = (cls?.guns ?? 0) > 0;
        const tender = cls?.role === "tender";
        const assault = cls?.role === "assault";
        // a tender's job is distress calls; lane and supply stay console orders so they are not picked by mistake
        const carrier = !armed && !tender && (cls?.carries ?? []).length > 0;
        const cargo = Object.entries(s.stock).map(([c, q]) => `${Math.floor(q)} ${c}`).join(", ");
        return (
          <div key={s.id} className="rounded-md border border-border p-2">
            <div className="flex flex-wrap items-baseline gap-x-2">
              <span className="font-mono">#{s.id}</span>
              <span className="font-medium">{className(rules, s.cls)}{s.name ? ` “${s.name}”` : ""}</span>
              <span className="text-muted-foreground">at {rel(s.relative)} · {s.efficiency.toFixed(0)}% · {s.hexesPerUpdate} hex{s.hexesPerUpdate === 1 ? "" : "es"}/update (tech {s.tech.toFixed(0)}) · {Math.floor(s.load)}/{s.hold}{cargo ? ` (${cargo})` : ""}{s.tank > 0 ? <> · <span className={s.fuel < s.tank * 0.15 ? "text-destructive" : undefined}>fuel {Math.floor(s.fuel)}/{s.tank}</span></> : null}{s.crewNeeded > 0 ? <> · <span className={s.crew < s.crewNeeded ? "text-destructive" : undefined}>crew {Math.floor(s.crew)}/{s.crewNeeded}</span></> : null}</span>
            </div>
            <div className="text-muted-foreground">{s.handLeg ? `sailing by hand${s.destRelative ? ` to ${rel(s.destRelative)}` : ", holding"} — ${going} resumes on arrival` : going}{s.note ? ` · ${s.note}` : ""}</div>
            {s.markedBy.length > 0 && <div className="text-destructive">Fired on {s.markedBy.join(", ")} in peacetime: they may shoot her on sight for now.</div>}
            <div className="mt-1 flex flex-wrap gap-1">
              <Button size="sm" variant="secondary" disabled={busy} title={s.mission || s.lane ? "sail her by hand; her standing order resumes when she arrives" : undefined} onClick={() => onSail(s)}>Sail…</Button>
              {fisher && (s.mission === "fish"
                ? <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "fish", ship: s.id, clear: true })}>Stop fishing</Button>
                : <Button size="sm" variant="secondary" disabled={busy || !s.docked} title={s.docked ? "roam the grounds near this harbour, fish, land the catch here, repeat" : "give the order while docked in the home harbour"} onClick={() => void onCommand({ verb: "fish", ship: s.id, x: s.at.x, y: s.at.y })}>Fish from here</Button>)}
              {armed && <Button size="sm" variant="secondary" disabled={busy} title="fire on a ship you can see; it happens now, and she answers" onClick={() => setDialog({ kind: "fire", ship: s })}>Fire…</Button>}
              {assault && <Button size="sm" variant="secondary" disabled={busy || s.load < 1} title={s.load < 1 ? "load mil and civ in harbour first" : "put everyone aboard ashore on unowned land next to her"} onClick={() => setDialog({ kind: "land", ship: s })}>Land…</Button>}
              {armed && (s.mission && MILITARY.includes(s.mission)
                ? <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: s.mission!, ship: s.id, clear: true })}>Stop {s.mission}</Button>
                : <>
                    <Button size="sm" variant="secondary" disabled={busy || !s.docked} title={s.docked ? "wander the water near this harbour, going where you have not looked lately; home for supplies" : "give the order in the harbour she should come home to"} onClick={() => void onCommand({ verb: "search", ship: s.id })}>Search from here</Button>
                    <Button size="sm" variant="ghost" disabled={busy || view.ships.length < 2} onClick={() => setDialog({ kind: "escort", ship: s })}>Escort…</Button>
                  </>)}
              {carrier && (s.mission === "supply"
                ? <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "supply", ship: s.id, clear: true })}>Stop supply</Button>
                : <Button size="sm" variant="secondary" disabled={busy || !s.docked} title={s.docked ? "carry whatever your harbours' thresholds are short of, from any harbour that can spare it; refit here" : "give the order while docked in the harbour it should refit at"} onClick={() => void onCommand({ verb: "supply", ship: s.id, x: s.at.x, y: s.at.y })}>Supply from here</Button>)}
              {miner && (s.mission === "mine"
                ? <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "mine", ship: s.id, clear: true })}>Stop mining</Button>
                : <Button size="sm" variant="secondary" disabled={busy || !s.docked} title={s.docked ? "roam the nodule fields near this harbour, mine, land the iron here, repeat; comes home to refit by itself" : "give the order while docked in the home harbour"} onClick={() => void onCommand({ verb: "mine", ship: s.id, x: s.at.x, y: s.at.y })}>Mine from here</Button>)}
              {s.dest && !s.lane && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "sail", ship: s.id, clear: true })}>Hold</Button>}
              <Button size="sm" variant="ghost" disabled={busy || !s.docked} onClick={() => setDialog({ kind: "load", ship: s })}>Load…</Button>
              <Button size="sm" variant="ghost" disabled={busy || !s.docked || s.load <= 0} onClick={() => setDialog({ kind: "unload", ship: s })}>Unload…</Button>
              {(!tender || s.lane) && <Button size="sm" variant="ghost" disabled={busy || harbors.length < 2} onClick={() => setDialog({ kind: "lane", ship: s })}>{s.lane ? "Change lane…" : "Lane…"}</Button>}
              {tender && s.mission === "supply" && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "supply", ship: s.id, clear: true })}>Stop supply (put on call)</Button>}
              {s.lane && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "lane", ship: s.id, clear: true })}>Leave lane</Button>}
              {s.docked && (pendingScrap === s.id
                ? <Button size="sm" variant="danger" disabled={busy} onClick={() => { setPendingScrap(null); void onCommand({ verb: "scrap", ship: s.id }); }}>Confirm scrap</Button>
                : <Button size="sm" variant="ghost" disabled={busy} onClick={() => setPendingScrap(s.id)}>Scrap</Button>)}
              <Button size="sm" variant="ghost" onClick={() => setLogbook(l => l === s.id ? null : s.id)}>{logbook === s.id ? "Hide history" : "History"}</Button>
            </div>
            {logbook === s.id && <Logbook gameId={gameId} ship={s.id} updateNumber={view.updateNumber} />}
          </div>
        );
      })}
      {dialog?.kind === "fire" && <FireDialog ship={dialog.ship} view={view} rules={rules} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog?.kind === "land" && <LandDialog ship={dialog.ship} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog?.kind === "escort" && <EscortDialog ship={dialog.ship} view={view} rules={rules} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog && (dialog.kind === "load" || dialog.kind === "unload" || dialog.kind === "lane") && (dialog.kind === "lane"
        ? <LaneDialog ship={dialog.ship} harbors={harbors} view={view} rules={rules} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />
        : <CargoDialog kind={dialog.kind} ship={dialog.ship} view={view} rules={rules} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />)}
    </div>
  );
}

/** The ship's last few updates, a numbered line for each thing she did (issue #67). Refetched after each update. */
function Logbook({ gameId, ship, updateNumber }: { gameId: number; ship: number; updateNumber: number }) {
  const [book, setBook] = useState<ShipLogEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    shipHistory(gameId, ship).then(b => { if (live) { setBook(b); setError(null); } }).catch(e => { if (live) setError(String(e)); });
    return () => { live = false; };
  }, [gameId, ship, updateNumber]);
  if (error) return <p className="mt-1 text-destructive">{error}</p>;
  if (!book) return <p className="mt-1 text-muted-foreground">Reading the log…</p>;
  if (book.length === 0) return <p className="mt-1 text-muted-foreground">No log yet — it is written at each update.</p>;
  return (
    <div className="mt-1 space-y-1 border-t border-border pt-1">
      {book.map(e => (
        <div key={e.updateNumber}>
          <div className="font-medium">Update {e.updateNumber}</div>
          <ol className="list-decimal pl-4 text-muted-foreground">{e.lines.map((l, i) => <li key={i}>{l}</li>)}</ol>
        </div>
      ))}
    </div>
  );
}

/**
 * Fire on a contact (issue #68). Only fresh sightings are offered: an older one is where she was, not
 * where she is. The server checks range and whether she can be hit, and says why if not.
 */
function FireDialog({ ship, view, rules, busy, onClose, onCommand }: { ship: ShipView; view: CountryView; rules: Rules; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const fresh = view.contacts.filter(c => c.age <= 1);
  const [pick, setPick] = useState(fresh.length ? `${fresh[0].at.x},${fresh[0].at.y}` : "");
  const cls = rules.ships?.classes.find(c => c.id === ship.cls);
  const shells = Math.floor(ship.stock.shell ?? 0), guns = Math.floor(ship.stock.gun ?? 0);
  const name = (id: string | null) => id ? rules.ships?.classes.find(c => c.id === id)?.name ?? id : "unidentified ship";
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Fire from ship #{ship.id}</DialogTitle><DialogDescription>It happens now, and she answers with everything of hers in reach. {cls?.name} · range {cls?.range ?? 0} · {guns} guns, {shells} shells aboard. Firing on a country you are at peace with does not declare war, but marks this ship: they may shoot her on sight for a few updates.</DialogDescription></DialogHeader>
        {fresh.length === 0 ? <p className="text-sm text-muted-foreground">No fresh contacts. Your radar and your ships' lookouts find enemy ships at each update.</p> : (
          <label className="grid gap-1 text-sm">Target
            <Select value={pick} onChange={e => setPick(e.target.value)}>
              {fresh.map((c, i) => <option key={i} value={`${c.at.x},${c.at.y}`}>{name(c.cls)}{c.ownerName ? ` of ${c.ownerName}` : ""} at {rel(c.relative)} · {c.band}{c.age ? ", last update" : ""}</option>)}
            </Select>
          </label>
        )}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={busy || !pick} onClick={async () => { const [x, y] = pick.split(",").map(Number); const c = fresh.find(k => k.at.x === x && k.at.y === y); await onCommand({ verb: "fire", ship: ship.id, x, y, type: c?.cls ?? undefined }); onClose(); }}>Fire</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Put a landing party ashore (issue #193): the unowned land sectors next to the ship, and what landing
 * on each would mean. The server makes the sector yours and says whether the ground feeds them.
 */
function LandDialog({ ship, view, busy, onClose, onCommand }: { ship: ShipView; view: CountryView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const shores = ["e", "ne", "nw", "w", "sw", "se"]
    .map(d => neighbour(view, ship.at, d))
    .map(at => view.sectors.find(o => o.at.x === at.x && o.at.y === at.y))
    // unowned coast to settle, and at war, enemy coast to assault (issue #206)
    .filter((o): o is NonNullable<typeof o> => !!o && o.terrain !== "ocean" && !o.sanctuary
      && (o.owner < 0 || (o.owner !== view.countryId && !!o.ownerName && (view.atWarWith ?? []).includes(o.ownerName))));
  const [pick, setPick] = useState(shores[0] ? `${shores[0].at.x},${shores[0].at.y}` : "");
  const mil = Math.floor(ship.stock.mil ?? 0), civ = Math.floor(ship.stock.civ ?? 0);
  const chosen = shores.find(o => `${o.at.x},${o.at.y}` === pick);
  const feeds = chosen?.resources ? Math.floor(300 * chosen.resources.fertility / 100) : null;
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Land from ship #{ship.id}</DialogTitle><DialogDescription>Everyone aboard goes ashore — {mil} mil and {civ} civ — and the sector becomes yours. From there they can explore the island, and a harbour on the coast lets ordinary shipping reach it.</DialogDescription></DialogHeader>
        {shores.length === 0 ? <p className="text-sm text-muted-foreground">No unowned land next to her. Sail her alongside the coast first.</p> : (
          <label className="grid gap-1 text-sm">Where
            <Select value={pick} onChange={e => setPick(e.target.value)}>
              {shores.map(o => <option key={`${o.at.x},${o.at.y}`} value={`${o.at.x},${o.at.y}`}>{o.relative.x},{o.relative.y} · {bearing(view, ship.at, o.at)} · {o.owner >= 0 ? `ASSAULT ${o.ownerName}'s ${o.designation ?? "sector"}` : o.terrain}{o.owner < 0 && o.resources ? ` · fertility ${o.resources.fertility}` : ""}</option>)}
            </Select>
          </label>
        )}
        {chosen && chosen.owner >= 0 && <p className="text-xs text-destructive">An assault: your {mil} mil fight theirs man for man, and their military next door joins in. Win and the sector is yours with its people; lose and every soldier aboard is gone. The civilians go ashore only if it is taken.</p>}
        {chosen && chosen.owner < 0 && feeds !== null && mil + civ > feeds && <p className="text-xs text-destructive">That ground feeds about {feeds} people and they bring no food: send some soon, or pick richer land.</p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant={chosen && chosen.owner >= 0 ? "danger" : "primary"} disabled={busy || !chosen} onClick={async () => { if (!chosen) return; await onCommand({ verb: "land", ship: ship.id, x: chosen.at.x, y: chosen.at.y }); onClose(); }}>{chosen && chosen.owner >= 0 ? "Assault" : "Land them"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Stay with one of your other ships (issue #68). */
function EscortDialog({ ship, view, rules, busy, onClose, onCommand }: { ship: ShipView; view: CountryView; rules: Rules; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const others = view.ships.filter(o => o.id !== ship.id);
  const [ward, setWard] = useState(String(others[0]?.id ?? ""));
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Escort with ship #{ship.id}</DialogTitle><DialogDescription>She stays with the ship you choose and fights what attacks it at war. She comes home for shells, fuel and repairs, and goes back to her charge afterwards.</DialogDescription></DialogHeader>
        <label className="grid gap-1 text-sm">Stay with
          <Select value={ward} onChange={e => setWard(e.target.value)}>
            {others.map(o => <option key={o.id} value={o.id}>#{o.id} {className(rules, o.cls)}{o.name ? ` “${o.name}”` : ""} at {rel(o.relative)}</option>)}
          </Select>
        </label>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !ward} onClick={async () => { await onCommand({ verb: "escort", ship: ship.id, ward: Number(ward) }); onClose(); }}>Escort</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
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
        <DialogHeader><DialogTitle>Shipping lane for ship #{ship.id}</DialogTitle><DialogDescription>A standing order: load at the first harbour, sail to the second, unload, come back — every update, until you clear it. With nothing ticked it carries only what the second harbour's thresholds are short of, and waits when it wants nothing.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label>Load at<Select value={from} onChange={e => setFrom(e.target.value)}>{harbors.map(h => <option key={key(h)} value={key(h)}>{rel(h.relative)} · {h.designation}</option>)}</Select></label>
          <label>Unload at<Select value={to} onChange={e => setTo(e.target.value)}>{harbors.map(h => <option key={key(h)} value={key(h)}>{rel(h.relative)} · {h.designation}</option>)}</Select></label>
          <div>Carry <span className="text-muted-foreground">(none ticked = what the far end's thresholds want)</span>
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
