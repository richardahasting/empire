import { useEffect, useMemo, useState, type ReactNode } from "react";
import { estimate, type CommandRequest, type Coord, type CountryView, type Estimate, type Macro, type Rules, type SectorView } from "@/api/client";
import { ContextMenu, ContextMenuContent, ContextMenuItem, ContextMenuLabel, ContextMenuSeparator, ContextMenuTrigger } from "@/components/ui/context-menu";
import { describeMacro } from "@/game/Macros";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";

type DialogKind = "move" | "explore" | "designate" | "threshold" | "deliver" | "road" | "rail" | "railship" | null;

export interface PickSpec { verb: "move" | "explore" | "distribute"; from: SectorView; commodity: string; qty: number; supply?: boolean }

interface Props {
  gameId: number; view: CountryView; rules: Rules; sector: SectorView | null;
  onCommand: (c: CommandRequest) => Promise<void>; busy: boolean; children: ReactNode;
  /** Enter targeting mode: the parent shows estimates as the pointer moves and commits on click. */
  onStartPick: (spec: PickSpec) => void;
  /** What happened in this sector last update, in order (issue #49). */
  history?: string[]; historyUpdate?: number;
  /** Macros (issue #47): the ten slots, whether one is recording, and the actions. */
  macros?: Macro[]; recording?: boolean;
  onRecordMacro?: () => void; onStopRecording?: () => void; onRunMacro?: (slot: number, at: Coord) => void; onRunMacroDialog?: () => void; onOpenMacros?: () => void;
}

/**
 * Right-click a sector on the map: every action for that sector, from here. Move and
 * explore show the route and the mobility it would cost before you commit.
 */
export function SectorMenu({ gameId, view, rules, sector: s, onCommand, busy, children, onStartPick, history, historyUpdate, macros = [], recording, onRecordMacro, onStopRecording, onRunMacro, onRunMacroDialog, onOpenMacros }: Props) {
  const [dialog, setDialog] = useState<DialogKind>(null);
  const rel = (c: Coord) => `${c.x},${c.y}`;
  const byRel = useMemo(() => { const m = new Map<string, SectorView>(); for (const x of view.sectors) m.set(rel(x.relative), x); return m; }, [view]);
  const adjacentUnowned = useMemo(() => {
    if (!s) return [] as SectorView[];
    const out: SectorView[] = [];
    for (const o of view.sectors) if (!o.full && o.owner < 0 && o.terrain !== "ocean" && hexDist(o.at, s.at, view, o) === 1) out.push(o);
    return out;
  }, [s, view]);

  const owned = !!s && s.full;
  return (
    <>
      <ContextMenu>
        <ContextMenuTrigger asChild>{children}</ContextMenuTrigger>
        <ContextMenuContent>
          {!s && <ContextMenuLabel>Unexplored</ContextMenuLabel>}
          {s && !owned && <ContextMenuLabel>{rel(s.relative)} · {s.terrain}{s.owner >= 0 ? " · foreign" : " · unowned"}</ContextMenuLabel>}
          {s && owned && (
            <>
              <ContextMenuLabel className="font-semibold text-popover-foreground">Sector {rel(s.relative)}</ContextMenuLabel>
              <Attributes s={s} view={view} />
              {history && history.length > 0 && (
                <div className="max-h-40 overflow-auto px-2 pb-1 text-xs text-muted-foreground">
                  <div className="text-popover-foreground">Last update{historyUpdate ? ` (${historyUpdate})` : ""}</div>
                  <ol className="list-decimal pl-4">{history.map((l, i) => <li key={i}>{l}</li>)}</ol>
                </div>
              )}
              <ContextMenuSeparator />
              <ContextMenuLabel>Inventory — click to move</ContextMenuLabel>
              {view.commodityIds.filter(c => (s.stock[c] ?? 0) >= 1).map(c => (
                <ContextMenuItem key={c} className="justify-between tabular-nums" onSelect={() => onStartPick({ verb: "move", from: s, commodity: c, qty: defaultMoveQty(c, s.stock[c] ?? 0) })}>
                  <span>{c}</span><span className="text-muted-foreground">{Math.floor(s.stock[c] ?? 0)}{s.held[c] ? ` (+${s.held[c].toFixed(0)} in transit)` : ""}</span>
                </ContextMenuItem>
              ))}
              <ContextMenuSeparator />
              <ContextMenuItem onSelect={() => setDialog("move")}>Move from here (choose amount)…</ContextMenuItem>
              <ContextMenuItem disabled={adjacentUnowned.length === 0} onSelect={() => setDialog("explore")}>Explore from here…</ContextMenuItem>
              <ContextMenuSeparator />
              <ContextMenuItem onSelect={() => setDialog("designate")}>Designate…</ContextMenuItem>
              <ContextMenuItem onSelect={() => setDialog("threshold")}>Set threshold…</ContextMenuItem>
              <ContextMenuItem onSelect={() => setDialog("deliver")}>Deliver to a neighbour…{Object.keys(s.deliveries).length ? ` (${Object.keys(s.deliveries).length} set)` : ""}</ContextMenuItem>
              <ContextMenuItem onSelect={() => setDialog("road")}>Build road…</ContextMenuItem>
              <ContextMenuItem disabled={view.levels.tech < (rules.rail?.techRequired ?? 60)} onSelect={() => setDialog("rail")}>Build rail…{view.levels.tech < (rules.rail?.techRequired ?? 60) ? ` (tech ${rules.rail?.techRequired ?? 60})` : ""}</ContextMenuItem>
              {isDepot(s, rules) && <ContextMenuItem onSelect={() => setDialog("railship")}>Ship by rail…</ContextMenuItem>}
              <ContextMenuSeparator />
              <ContextMenuItem onSelect={() => onStartPick({ verb: "distribute", from: s, commodity: "", qty: 0 })}>Send surplus to… (pick the centre on the map)</ContextMenuItem>
              <ContextMenuItem disabled={busy || (s.distCenter?.x === view.capital.x && s.distCenter?.y === view.capital.y)}
                onSelect={() => void onCommand({ verb: "distribute", x: s.at.x, y: s.at.y, x2: view.capital.x, y2: view.capital.y })}>Supply from the capital (set centre)</ContextMenuItem>
              {s.distCenter && <ContextMenuItem destructive disabled={busy} onSelect={() => void onCommand({ verb: "distribute", x: s.at.x, y: s.at.y, clear: true })}>Stop automatic supply (clear centre)</ContextMenuItem>}
              <ContextMenuSeparator />
              <ContextMenuLabel>Macros{macros.length ? " — click to run here" : ""}</ContextMenuLabel>
              {macros.map(m => (
                <ContextMenuItem key={m.slot} disabled={busy} title={describeMacro(m)} onSelect={() => onRunMacro?.(m.slot, s.at)}>
                  <span className="mr-2 font-mono text-xs text-muted-foreground">{m.slot === 10 ? "0" : m.slot}</span>{m.name}
                  <span className="ml-2 truncate text-xs text-muted-foreground">{describeMacro(m)}</span>
                </ContextMenuItem>
              ))}
              {macros.length > 0 && <ContextMenuItem disabled={busy} onSelect={() => onRunMacroDialog?.()}>Run a macro on many sectors…</ContextMenuItem>}
              {recording ? <ContextMenuItem destructive onSelect={() => onStopRecording?.()}>Stop recording</ContextMenuItem>
                         : <ContextMenuItem onSelect={() => onRecordMacro?.()}>Record macro…</ContextMenuItem>}
              <ContextMenuItem onSelect={() => onOpenMacros?.()}>Macros… (see what each key does)</ContextMenuItem>
            </>
          )}
        </ContextMenuContent>
      </ContextMenu>

      {s && owned && dialog === "move" && <MoveDialog gameId={gameId} view={view} from={s} byRel={byRel} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} onPick={(commodity, qty) => { setDialog(null); onStartPick({ verb: "move", from: s, commodity, qty }); }} />}
      {s && owned && dialog === "explore" && <ExploreDialog gameId={gameId} view={view} from={s} targets={adjacentUnowned} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} onPick={(civs, supply) => { setDialog(null); onStartPick({ verb: "explore", from: s, commodity: "civ", qty: civs, supply }); }} />}
      {s && owned && dialog === "designate" && <DesignateDialog view={view} rules={rules} sector={s} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} />}
      {s && owned && dialog === "threshold" && <ThresholdDialog view={view} rules={rules} sector={s} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} />}
      {s && owned && dialog === "deliver" && <DeliverDialog view={view} sector={s} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} />}
      {s && owned && dialog === "road" && <RoadDialog rules={rules} sector={s} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} />}
      {s && owned && dialog === "rail" && <RailDialog rules={rules} sector={s} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} />}
      {s && owned && dialog === "railship" && <RailShipDialog gameId={gameId} view={view} rules={rules} from={s} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} />}
    </>
  );
}

/** Goods prefill in full; people prefill by half, because emptying a sector of its people is rarely what you meant. */
export function defaultMoveQty(commodity: string, stock: number): number {
  const people = commodity === "civ" || commodity === "mil" || commodity === "uw";
  return Math.floor(people ? stock / 2 : stock);
}

/** The sector at a glance, inside the menu. */
function Attributes({ s, view }: { s: SectorView; view: CountryView }) {
  const th = Object.keys(s.thresholds).length;
  const held = Object.values(s.held).reduce((a, b) => a + b, 0);
  return (
    <div className="px-2 pb-1 text-xs text-muted-foreground">
      <div className="text-popover-foreground">{s.designation} · {s.terrain} · eff {s.efficiency.toFixed(0)}% · mob {s.mobility.toFixed(0)}{(s.roadLevel > 0 || s.roadTarget > 0) && ` · road ${s.roadLevel.toFixed(0)}${s.roadTarget > s.roadLevel ? ` → ${s.roadTarget.toFixed(0)}` : ""}`}{(s.railLevel > 0 || s.railTarget > 0) && ` · rail ${s.railLevel.toFixed(0)}${s.railTarget > s.railLevel ? ` → ${s.railTarget.toFixed(0)}` : ""}`}</div>
      {s.resources && <div>fert {s.resources.fertility} · min {s.resources.minerals} · gold {s.resources.gold} · oil {s.resources.oil} · uran {s.resources.uranium}</div>}
      <div>{Object.keys(s.deliveries).length > 0 && `deliver ${Object.entries(s.deliveries).map(([c, d]) => `${c}→${d.dir}>${d.threshold}`).join(" ")} · `}centre {s.distCenter ? `${view.sectors.find(o => o.at.x === s.distCenter!.x && o.at.y === s.distCenter!.y)?.relative.x ?? "?"},${view.sectors.find(o => o.at.x === s.distCenter!.x && o.at.y === s.distCenter!.y)?.relative.y ?? "?"}` : "none"} · {th} threshold{th === 1 ? "" : "s"}{held > 0 && ` · ${held.toFixed(0)} in transit`}</div>
    </div>
  );
}

/** Hex distance between two sectors via their relative coordinates (odd-r offset, parity from absolute y). */
function hexDist(a: Coord, b: Coord, _v: CountryView, _o: SectorView): number {
  const cube = (c: Coord) => { const q = c.x - (c.y - (c.y & 1)) / 2; const r = c.y; return [q, r, -q - r]; };
  const [aq, ar, as] = cube(a), [bq, br, bs] = cube(b);
  return (Math.abs(aq - bq) + Math.abs(ar - br) + Math.abs(as - bs)) / 2;
}

function EstimateView({ e, unit }: { e: Estimate | null; unit: string }) {
  if (!e) return <p className="text-xs text-muted-foreground">Estimating…</p>;
  if (!e.ok) return <p className="text-xs text-destructive">{e.error}</p>;
  const hops = e.path.length - 1;
  return (
    <div className="space-y-1 rounded-md border border-border bg-muted p-2 text-xs">
      <div>Route: {e.path.map(c => `${c.x},${c.y}`).join(" → ")} <span className="text-muted-foreground">({hops} hop{hops === 1 ? "" : "s"}, reach {e.reach} per update)</span></div>
      <div>Mobility: <span className="tabular-nums font-medium">{e.totalMobility.toFixed(1)}</span> total, paid by the sending sector ({e.hopCosts.map(c => c.toFixed(1)).join(" + ")} per hop, of {e.sourceMobility.toFixed(0)} there)</div>
      <div><span className="font-medium">{e.arrivesQty.toFixed(0)} {unit}</span> move now{e.heldQty > 0 && <>, <span className="font-medium">{e.heldQty.toFixed(0)}</span> stay behind for lack of mobility</>}</div>
    </div>
  );
}

function MoveDialog({ gameId, view, from, byRel, onClose, onCommand, busy, onPick }: { gameId: number; view: CountryView; from: SectorView; byRel: Map<string, SectorView>; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean; onPick: (commodity: string, qty: number) => void }) {
  const [commodity, setCommodity] = useState(from.stock["civ"] > 0 ? "civ" : "food");
  const [qty, setQty] = useState("");
  const [dest, setDest] = useState("0,0");
  const [est, setEst] = useState<Estimate | null>(null);
  const target = byRel.get(dest.replace(/\s/g, ""));
  const n = Number(qty);
  useEffect(() => {
    setEst(null);
    if (!target || !(n > 0)) return;
    let live = true;
    const t = setTimeout(() => { estimate(gameId, { verb: "move", x: from.at.x, y: from.at.y, x2: target.at.x, y2: target.at.y, commodity, amount: n }).then(e => { if (live) setEst(e); }).catch(e => { if (live) setEst({ ok: false, error: (e as Error).message, path: [], hopCosts: [], totalMobility: 0, reach: 0, arrivesQty: 0, heldQty: 0, holdsAt: null, available: 0, sourceMobility: 0 }); }); }, 250);
    return () => { live = false; clearTimeout(t); };
  }, [gameId, from, target, commodity, n]);
  const ownedList = [...byRel.values()].filter(x => x.full && !(x.at.x === from.at.x && x.at.y === from.at.y));
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Move from {from.relative.x},{from.relative.y}</DialogTitle><DialogDescription>Moves now. This sector pays the whole route's mobility; if it runs short, what fits moves and the rest stays here.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label>Commodity
            <Select value={commodity} onChange={e => setCommodity(e.target.value)}>
              {view.commodityIds.map(c => <option key={c} value={c}>{c} ({(from.stock[c] ?? 0).toFixed(0)} here)</option>)}
            </Select>
          </label>
          <label>Quantity <span className="text-muted-foreground">(up to {(from.stock[commodity] ?? 0).toFixed(0)})</span>
            <Input value={qty} onChange={e => setQty(e.target.value)} inputMode="numeric" autoFocus />
          </label>
          <label>Destination (x,y relative to your capital)
            <div className="flex gap-2">
              <Button variant="secondary" disabled={!(n > 0)} onClick={() => onPick(commodity, n)}>Pick on map…</Button>
              <Input value={dest} onChange={e => setDest(e.target.value)} className="w-28" />
              <Select value={target ? `${target.relative.x},${target.relative.y}` : ""} onChange={e => setDest(e.target.value)}>
                <option value="">pick an owned sector…</option>
                {ownedList.map(x => <option key={`${x.at.x},${x.at.y}`} value={`${x.relative.x},${x.relative.y}`}>{x.relative.x},{x.relative.y} · {x.designation}</option>)}
              </Select>
            </div>
          </label>
          {target && n > 0 ? <EstimateView e={est} unit={commodity} /> : <p className="text-xs text-muted-foreground">Enter a quantity, then pick the destination on the map or type it.</p>}
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !target || !(n > 0) || !est?.ok} onClick={async () => { await onCommand({ verb: "move", x: from.at.x, y: from.at.y, x2: target!.at.x, y2: target!.at.y, commodity, amount: n }); onClose(); }}>Move</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** After a claim: point the new sector at the capital and give it food/civ thresholds so settlers eat. Three commands, three BTUs. */
export async function supplyFromCapital(view: CountryView, at: Coord, onCommand: (c: CommandRequest) => Promise<void>) {
  await onCommand({ verb: "distribute", x: at.x, y: at.y, x2: view.capital.x, y2: view.capital.y });
  await onCommand({ verb: "threshold", x: at.x, y: at.y, commodity: "food", amount: 100 });
  await onCommand({ verb: "threshold", x: at.x, y: at.y, commodity: "civ", amount: 100 });
}

function ExploreDialog({ gameId, view, from, targets, onClose, onCommand, busy, onPick }: { gameId: number; view: CountryView; from: SectorView; targets: SectorView[]; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean; onPick: (civs: number, supply: boolean) => void }) {
  const [targetKey, setTargetKey] = useState(targets[0] ? `${targets[0].at.x},${targets[0].at.y}` : "");
  const [civs, setCivs] = useState("20");
  const [supply, setSupply] = useState(true);
  const [est, setEst] = useState<Estimate | null>(null);
  const target = targets.find(t => `${t.at.x},${t.at.y}` === targetKey);
  const n = Number(civs);
  useEffect(() => {
    setEst(null);
    if (!target || !(n > 0)) return;
    let live = true;
    estimate(gameId, { verb: "explore", x: from.at.x, y: from.at.y, x2: target.at.x, y2: target.at.y, amount: n }).then(e => { if (live) setEst(e); }).catch(() => {});
    return () => { live = false; };
  }, [gameId, from, target, n]);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Explore from {from.relative.x},{from.relative.y}</DialogTitle><DialogDescription>Civilians walk into an adjacent unowned sector and claim it. Immediate; costs this sector's mobility.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label>Into
            <Select value={targetKey} onChange={e => setTargetKey(e.target.value)}>
              {targets.map(t => <option key={`${t.at.x},${t.at.y}`} value={`${t.at.x},${t.at.y}`}>{t.relative.x},{t.relative.y} · {t.terrain}</option>)}
            </Select>
          </label>
          <label>Civilians <span className="text-muted-foreground">({(from.stock["civ"] ?? 0).toFixed(0)} here, mobility {from.mobility.toFixed(0)})</span>
            <Input value={civs} onChange={e => setCivs(e.target.value)} inputMode="numeric" autoFocus />
          </label>
          {target && n > 0 && <EstimateView e={est} unit="civilians" />}
          <label className="flex items-center gap-2 text-xs"><input type="checkbox" checked={supply} onChange={e => setSupply(e.target.checked)} />
            Supply it from the capital afterwards: distribution centre = capital, thresholds food 100 and civ 100 (3 BTU). Settlers with no food supply starve.</label>
        </div>
        <DialogFooter>
          <Button variant="secondary" disabled={!(n > 0)} onClick={() => onPick(n, supply)}>Pick on map…</Button>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !target || !(n > 0) || !est?.ok} onClick={async () => { await onCommand({ verb: "explore", x: from.at.x, y: from.at.y, x2: target!.at.x, y2: target!.at.y, amount: n }); if (supply) await supplyFromCapital(view, target!.at, onCommand); onClose(); }}>Explore</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** This sector, every sector, or every sector like this one (issue #38). Each sector pays its own BTU. */
function ScopeSelect({ s, scope, setScope }: { s: SectorView; scope: string; setScope: (v: string) => void }) {
  return (
    <label>Apply to
      <Select value={scope} onChange={e => setScope(e.target.value)}>
        <option value="">this sector only</option>
        <option value="*">all my sectors (1 BTU each)</option>
        {s.designation && <option value={`*:${s.designation}`}>all my {s.designation} sectors (1 BTU each)</option>}
      </Select>
    </label>
  );
}

function DesignateDialog({ view, rules, sector: s, onClose, onCommand, busy }: { view: CountryView; rules: Rules; sector: SectorView; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean }) {
  const options = rules.sectorTypes.filter(t => !(t.flags ?? []).some(f => f === "no_designate" || f === "undesignated") && (t.minTech ?? 0) <= view.levels.tech && (!t.terrainRequired || t.terrainRequired.includes(s.terrain)));
  const [type, setType] = useState(options[0]?.id ?? "");
  const [scope, setScope] = useState("");
  const t = options.find(o => o.id === type);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Designate {s.relative.x},{s.relative.y}</DialogTitle><DialogDescription>Currently {s.designation} at {s.efficiency.toFixed(0)}%. Changing it may reset efficiency.</DialogDescription></DialogHeader>
        <label className="text-sm">Designation
          <Select value={type} onChange={e => setType(e.target.value)}>{options.map(o => <option key={o.id} value={o.id}>{o.glyph} {o.id}</option>)}</Select>
        </label>
        {t && <p className="text-xs text-muted-foreground">{t.category}{t.produces && ` · produces ${Object.keys(t.produces).join(", ")}`}{t.consumes && ` · consumes ${Object.keys(t.consumes).join(", ")}`}{t.build && ` · builds with ${Object.keys(t.build).join(", ")}`}</p>}
        {type !== "capital" && <div className="text-sm"><ScopeSelect s={s} scope={scope} setScope={setScope} /></div>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !type || (!scope && type === s.designation)} onClick={async () => {
            if (type === "capital" && !window.confirm(`Move your capital to ${s.relative.x},${s.relative.y}? BTUs accrue from the civilians in the capital, and every relative coordinate will shift.`)) return;
            await onCommand({ verb: "designate", x: s.at.x, y: s.at.y, type, scope: type === "capital" ? undefined : scope || undefined }); onClose();
          }}>Designate</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ThresholdDialog({ view, rules, sector: s, onClose, onCommand, busy }: { view: CountryView; rules: Rules; sector: SectorView; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean }) {
  const [commodity, setCommodity] = useState("food");
  const [amount, setAmount] = useState(s.thresholds["food"] !== undefined ? String(s.thresholds["food"]) : "");
  const [scope, setScope] = useState("");
  // a mixed selection (all my sectors) scales goods by designation, e.g. warehouses ×10 — never people; "all my <type>" and one sector set it as typed
  const mult = Object.entries(rules.massThresholdMultiplierByType ?? {}).filter(([, m]) => m > 0 && m !== 1);
  const person = !!rules.commodities.find(c => c.id === commodity)?.isPerson;
  const scaledNote = scope === "*" && mult.length > 0 && amount !== "" ? (person ? `${commodity} is people: ${mult.map(([t]) => `${t}s`).join(", ")} get ${Number(amount).toFixed(0)} like everyone else` : mult.map(([t, m]) => `${t}s get ${(Number(amount) * m).toFixed(0)} (×${m})`).join(", ")) : null;
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Threshold at {s.relative.x},{s.relative.y}</DialogTitle><DialogDescription>Below the threshold this sector pulls from its distribution centre; above it, it pushes the surplus there. Needs a centre ({s.distCenter ? "set" : "none yet"}).</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label>Commodity
            <Select value={commodity} onChange={e => { setCommodity(e.target.value); setAmount(s.thresholds[e.target.value] !== undefined ? String(s.thresholds[e.target.value]) : ""); }}>
              {view.commodityIds.map(c => <option key={c} value={c}>{c} (stock {(s.stock[c] ?? 0).toFixed(0)}{s.thresholds[c] !== undefined ? `, threshold ${s.thresholds[c]}` : ""})</option>)}
            </Select>
          </label>
          <label>Amount<Input value={amount} onChange={e => setAmount(e.target.value)} inputMode="numeric" autoFocus /></label>
          <ScopeSelect s={s} scope={scope} setScope={setScope} />
          {scaledNote && <p className="text-xs text-muted-foreground">{scaledNote}</p>}
        </div>
        <DialogFooter>
          {(scope || s.thresholds[commodity] !== undefined) && <Button variant="danger" disabled={busy} onClick={async () => { await onCommand({ verb: "threshold", x: s.at.x, y: s.at.y, commodity, clear: true, scope: scope || undefined }); onClose(); }}>Clear</Button>}
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || amount === ""} onClick={async () => { await onCommand({ verb: "threshold", x: s.at.x, y: s.at.y, commodity, amount: Number(amount), scope: scope || undefined }); onClose(); }}>Set</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const DIRECTIONS: [string, string][] = [["e", "east"], ["ne", "north-east"], ["nw", "north-west"], ["w", "west"], ["sw", "south-west"], ["se", "south-east"]];

/** The original's deliver: a standing order per commodity — above the threshold, one hex that way, every update (issue #45). */
function DeliverDialog({ view, sector: s, onClose, onCommand, busy }: { view: CountryView; sector: SectorView; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean }) {
  const existing = Object.keys(s.deliveries);
  const [commodity, setCommodity] = useState(existing[0] ?? view.commodityIds.find(c => !["civ", "mil", "uw"].includes(c) && (s.stock[c] ?? 0) > 0) ?? "food");
  const [dir, setDir] = useState(s.deliveries[commodity]?.dir ?? "e");
  const [threshold, setThreshold] = useState(s.deliveries[commodity] ? String(s.deliveries[commodity].threshold) : "0");
  const [scope, setScope] = useState("");
  const pick = (c: string) => { setCommodity(c); const d = s.deliveries[c]; if (d) { setDir(d.dir); setThreshold(String(d.threshold)); } };
  const n = Number(threshold);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Deliver from {s.relative.x},{s.relative.y}</DialogTitle><DialogDescription>A standing order, as in the original. Every update, whatever is above the threshold moves one hex in that direction, this sector paying the mobility, if that hex is yours. The receiving sector applies its own orders at the next update, so a chain advances one hop per update.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label>Commodity
            <Select value={commodity} onChange={e => pick(e.target.value)}>
              {view.commodityIds.map(c => <option key={c} value={c}>{c} (stock {(s.stock[c] ?? 0).toFixed(0)}{s.deliveries[c] ? `, delivering ${s.deliveries[c].dir} above ${s.deliveries[c].threshold}` : ""})</option>)}
            </Select>
          </label>
          <label>Direction
            <Select value={dir} onChange={e => setDir(e.target.value)}>
              {DIRECTIONS.map(([id, name]) => <option key={id} value={id}>{name} ({id})</option>)}
            </Select>
          </label>
          <label>Keep this much here (threshold)<Input value={threshold} onChange={e => setThreshold(e.target.value)} inputMode="numeric" autoFocus /></label>
          <ScopeSelect s={s} scope={scope} setScope={setScope} />
        </div>
        <DialogFooter>
          {(scope || s.deliveries[commodity]) && <Button variant="danger" disabled={busy} onClick={async () => { await onCommand({ verb: "deliver", x: s.at.x, y: s.at.y, commodity, clear: true, scope: scope || undefined }); onClose(); }}>Clear</Button>}
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !(n >= 0)} onClick={async () => { await onCommand({ verb: "deliver", x: s.at.x, y: s.at.y, commodity, direction: dir, amount: n, scope: scope || undefined }); onClose(); }}>Set</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function RoadDialog({ rules, sector: s, onClose, onCommand, busy }: { rules: Rules; sector: SectorView; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean }) {
  const [target, setTarget] = useState(String(Math.min(100, Math.max(s.roadTarget, Math.ceil(s.roadLevel / 10) * 10 + 20))));
  const [scope, setScope] = useState("");
  const t = Math.max(0, Math.min(100, Number(target) || 0));
  const r = rules.road;
  const mult = r?.costMultiplierByTerrain?.[s.terrain] ?? 1;
  const cap = r?.maxLevelByTerrain?.[s.terrain] ?? 100;
  const points = Math.max(0, t - s.roadLevel);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Build road at {s.relative.x},{s.relative.y}</DialogTitle><DialogDescription>A standing order. Each update this sector spends its own materials, your cash and its workers to pave toward the target, up to {r?.maxPointsPerUpdate ?? "?"} points per update. Roads cut the mobility cost of everything entering the sector, and decay unless maintained.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <div className="text-xs text-muted-foreground">Now {s.roadLevel.toFixed(0)}{s.roadTarget > 0 ? `, ordered to ${s.roadTarget.toFixed(0)}` : ""} · {s.terrain} caps at {cap} · cost ×{mult}</div>
          <label>Target level (0 cancels)<Input value={target} onChange={e => setTarget(e.target.value)} inputMode="numeric" autoFocus /></label>
          {points > 0 && r && (
            <div className="rounded-md border border-border bg-muted p-2 text-xs">
              To reach {t}: {Object.entries(r.buildMaterialsPerPoint ?? {}).map(([k, v]) => `${(v * mult * points).toFixed(0)} ${k}`).join(", ")}, {(r.workPerPoint * mult * points).toFixed(0)} work · about {Math.ceil(points / (r.maxPointsPerUpdate || 1))} update{Math.ceil(points / (r.maxPointsPerUpdate || 1)) === 1 ? "" : "s"} if supplied
              {t > cap && <div className="text-muted-foreground">{s.terrain} caps at {cap}: the order will be {cap}</div>}
            </div>
          )}
          <ScopeSelect s={s} scope={scope} setScope={setScope} />
          {scope && <p className="text-xs text-muted-foreground">Sectors whose terrain caps below {t} are ordered to their cap; the reply says which.</p>}
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy} onClick={async () => { await onCommand({ verb: "build_road", x: s.at.x, y: s.at.y, amount: t, scope: scope || undefined }); onClose(); }}>{t === 0 ? "Cancel order" : "Order"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function isDepot(s: SectorView, rules: Rules): boolean {
  const t = rules.sectorTypes.find(x => x.id === s.designation);
  return !!t && (t.flags ?? []).includes("rail_endpoint");
}

function RailDialog({ rules, sector: s, onClose, onCommand, busy }: { rules: Rules; sector: SectorView; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean }) {
  const r = rules.rail;
  const [target, setTarget] = useState(String(Math.min(100, Math.max(s.railTarget, Math.ceil(s.railLevel / 10) * 10 + 20))));
  const [scope, setScope] = useState("");
  const t = Math.max(0, Math.min(100, Number(target) || 0));
  const mult = r?.costMultiplierByTerrain?.[s.terrain] ?? 1;
  const cap = r?.maxLevelByTerrain?.[s.terrain] ?? 100;
  const points = Math.max(0, t - s.railLevel);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Build rail at {s.relative.x},{s.relative.y}</DialogTitle><DialogDescription>A standing order. Rail carries nothing on its own: it works only as a contiguous line of sectors at {r?.minLevelToCarry ?? 20}+ between two depots. Materials, cash and this sector's mobility per point; decays unless maintained.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <div className="text-xs text-muted-foreground">Now {s.railLevel.toFixed(0)}{s.railTarget > 0 ? `, ordered to ${s.railTarget.toFixed(0)}` : ""} · {s.terrain} caps at {cap} · cost ×{mult}</div>
          <label>Target level (0 cancels)<Input value={target} onChange={e => setTarget(e.target.value)} inputMode="numeric" autoFocus /></label>
          {points > 0 && r && (
            <div className="rounded-md border border-border bg-muted p-2 text-xs">
              To reach {t}: {Object.entries(r.buildMaterialsPerPoint ?? {}).map(([k, v]) => `${(v * mult * points).toFixed(0)} ${k}`).join(", ")}, {(mult * points).toFixed(0)} mobility · about {Math.ceil(points / (r.maxPointsPerUpdate || 1))} update{Math.ceil(points / (r.maxPointsPerUpdate || 1)) === 1 ? "" : "s"} if supplied
              {t > cap && <div className="text-muted-foreground">{s.terrain} caps at {cap}: the order will be {cap}</div>}
            </div>
          )}
          <ScopeSelect s={s} scope={scope} setScope={setScope} />
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy} onClick={async () => { await onCommand({ verb: "build_rail", x: s.at.x, y: s.at.y, amount: t, scope: scope || undefined }); onClose(); }}>{t === 0 ? "Cancel order" : "Order"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function RailShipDialog({ gameId, view, rules, from, onClose, onCommand, busy }: { gameId: number; view: CountryView; rules: Rules; from: SectorView; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean }) {
  const depots = view.sectors.filter(x => x.full && isDepot(x, rules) && !(x.at.x === from.at.x && x.at.y === from.at.y));
  const [destKey, setDestKey] = useState(depots[0] ? `${depots[0].at.x},${depots[0].at.y}` : "");
  const [commodity, setCommodity] = useState("iron");
  const [qty, setQty] = useState("");
  const [est, setEst] = useState<Estimate | null>(null);
  const dest = depots.find(d => `${d.at.x},${d.at.y}` === destKey);
  const n = Number(qty);
  useEffect(() => {
    setEst(null);
    if (!dest || !(n > 0)) return;
    let live = true;
    estimate(gameId, { verb: "rail", x: from.at.x, y: from.at.y, x2: dest.at.x, y2: dest.at.y, commodity, amount: n }).then(e => { if (live) setEst(e); }).catch(() => {});
    return () => { live = false; };
  }, [gameId, from, dest, commodity, n]);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Ship by rail from {from.relative.x},{from.relative.y}</DialogTitle><DialogDescription>Depot to depot along a contiguous line. Checked now; the train runs at the update, {rules.rail?.maxSectorsPerUpdate.base ?? 8} sectors per update, and holds on the line beyond that. Cost is by volume, not distance.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          {depots.length === 0 && <p className="text-destructive">You have no other depot.</p>}
          <label>To depot<Select value={destKey} onChange={e => setDestKey(e.target.value)}>{depots.map(d => <option key={`${d.at.x},${d.at.y}`} value={`${d.at.x},${d.at.y}`}>{d.relative.x},{d.relative.y} · eff {d.efficiency.toFixed(0)}%</option>)}</Select></label>
          <label>Commodity<Select value={commodity} onChange={e => setCommodity(e.target.value)}>{view.commodityIds.map(c => <option key={c} value={c}>{c} ({(from.stock[c] ?? 0).toFixed(0)} here)</option>)}</Select></label>
          <label>Quantity<Input value={qty} onChange={e => setQty(e.target.value)} inputMode="numeric" autoFocus /></label>
          {dest && n > 0 && (est ? (est.ok ? (
            <div className="space-y-1 rounded-md border border-border bg-muted p-2 text-xs">
              <div>Line: {est.path.map(c => `${c.x},${c.y}`).join(" → ")} <span className="text-muted-foreground">({est.path.length - 1} sectors, range {est.reach})</span></div>
              <div>Capacity along the line: {Math.min(...est.hopCosts).toFixed(0)} per update · cost ${est.totalMobility.toFixed(0)}</div>
              <div>{est.arrivesQty > 0 ? <><span className="font-medium">{est.arrivesQty.toFixed(0)}</span> arrive at the update</> : <>holds on the line at {est.holdsAt?.x},{est.holdsAt?.y} and continues next update</>}{est.heldQty > 0 && est.arrivesQty > 0 && <>, {est.heldQty.toFixed(0)} wait for capacity</>}</div>
            </div>
          ) : <p className="text-xs text-destructive">{est.error}</p>) : <p className="text-xs text-muted-foreground">Checking the line…</p>)}
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !dest || !(n > 0) || !est?.ok} onClick={async () => { await onCommand({ verb: "rail_ship", x: from.at.x, y: from.at.y, x2: dest!.at.x, y2: dest!.at.y, commodity, amount: n }); onClose(); }}>Schedule train</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
