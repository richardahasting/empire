import { useEffect, useMemo, useState, type ReactNode } from "react";
import { estimate, type CommandRequest, type Coord, type CountryView, type Estimate, type Rules, type SectorView } from "@/api/client";
import { ContextMenu, ContextMenuContent, ContextMenuItem, ContextMenuLabel, ContextMenuSeparator, ContextMenuTrigger } from "@/components/ui/context-menu";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";

type DialogKind = "move" | "explore" | "designate" | "threshold" | null;

export interface PickSpec { verb: "move" | "explore"; from: SectorView; commodity: string; qty: number }

interface Props {
  gameId: number; view: CountryView; rules: Rules; sector: SectorView | null;
  onCommand: (c: CommandRequest) => Promise<void>; busy: boolean; children: ReactNode;
  /** Enter targeting mode: the parent shows estimates as the pointer moves and commits on click. */
  onStartPick: (spec: PickSpec) => void;
}

/**
 * Right-click a sector on the map: every action for that sector, from here. Move and
 * explore show the route and the mobility it would cost before you commit.
 */
export function SectorMenu({ gameId, view, rules, sector: s, onCommand, busy, children, onStartPick }: Props) {
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
              <ContextMenuSeparator />
              <ContextMenuItem onSelect={() => setDialog("move")}>Move from here…</ContextMenuItem>
              <ContextMenuItem disabled={adjacentUnowned.length === 0} onSelect={() => setDialog("explore")}>Explore from here…</ContextMenuItem>
              <ContextMenuSeparator />
              <ContextMenuItem onSelect={() => setDialog("designate")}>Designate…</ContextMenuItem>
              <ContextMenuItem onSelect={() => setDialog("threshold")}>Set threshold…</ContextMenuItem>
              <ContextMenuSeparator />
              <ContextMenuItem disabled={busy || (s.distCenter?.x === view.capital.x && s.distCenter?.y === view.capital.y)}
                onSelect={() => void onCommand({ verb: "distribute", x: s.at.x, y: s.at.y, x2: view.capital.x, y2: view.capital.y })}>Distribute to capital</ContextMenuItem>
              {s.distCenter && <ContextMenuItem destructive disabled={busy} onSelect={() => void onCommand({ verb: "distribute", x: s.at.x, y: s.at.y, clear: true })}>Clear distribution centre</ContextMenuItem>}
            </>
          )}
        </ContextMenuContent>
      </ContextMenu>

      {s && owned && dialog === "move" && <MoveDialog gameId={gameId} view={view} from={s} byRel={byRel} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} onPick={(commodity, qty) => { setDialog(null); onStartPick({ verb: "move", from: s, commodity, qty }); }} />}
      {s && owned && dialog === "explore" && <ExploreDialog gameId={gameId} from={s} targets={adjacentUnowned} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} onPick={civs => { setDialog(null); onStartPick({ verb: "explore", from: s, commodity: "civ", qty: civs }); }} />}
      {s && owned && dialog === "designate" && <DesignateDialog view={view} rules={rules} sector={s} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} />}
      {s && owned && dialog === "threshold" && <ThresholdDialog view={view} sector={s} onClose={() => setDialog(null)} onCommand={onCommand} busy={busy} />}
    </>
  );
}

/** The sector at a glance, inside the menu. */
function Attributes({ s, view }: { s: SectorView; view: CountryView }) {
  const main = ["civ", "mil", "food", "iron", "lcm", "hcm", "oil", "pet"].filter(c => view.commodityIds.includes(c));
  const th = Object.keys(s.thresholds).length;
  const held = Object.values(s.held).reduce((a, b) => a + b, 0);
  return (
    <div className="px-2 pb-1 text-xs text-muted-foreground">
      <div className="text-popover-foreground">{s.designation} · {s.terrain} · eff {s.efficiency.toFixed(0)}% · mob {s.mobility.toFixed(0)}{s.roadLevel > 0 && ` · road ${s.roadLevel.toFixed(0)}`}</div>
      {s.resources && <div>fert {s.resources.fertility} · min {s.resources.minerals} · gold {s.resources.gold} · oil {s.resources.oil} · uran {s.resources.uranium}</div>}
      <div className="tabular-nums">{main.map(c => `${c} ${(s.stock[c] ?? 0).toFixed(0)}`).join(" · ")}</div>
      <div>centre {s.distCenter ? `${view.sectors.find(o => o.at.x === s.distCenter!.x && o.at.y === s.distCenter!.y)?.relative.x ?? "?"},${view.sectors.find(o => o.at.x === s.distCenter!.x && o.at.y === s.distCenter!.y)?.relative.y ?? "?"}` : "none"} · {th} threshold{th === 1 ? "" : "s"}{held > 0 && ` · ${held.toFixed(0)} in transit`}</div>
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
      <div>Mobility: <span className="tabular-nums font-medium">{e.totalMobility.toFixed(1)}</span> total, debited from each sector entered ({e.hopCosts.map(c => c.toFixed(1)).join(" + ")})</div>
      <div>This update: <span className="font-medium">{e.arrivesQty.toFixed(0)} {unit}</span> arrive{e.heldQty > 0 && e.holdsAt && <>, <span className="font-medium">{e.heldQty.toFixed(0)}</span> hold at {e.holdsAt.x},{e.holdsAt.y} and continue next update</>}</div>
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
        <DialogHeader><DialogTitle>Move from {from.relative.x},{from.relative.y}</DialogTitle><DialogDescription>Queued now, travels at the next update. Mobility comes out of every sector it enters.</DialogDescription></DialogHeader>
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
          <Button disabled={busy || !target || !(n > 0) || !est?.ok} onClick={async () => { await onCommand({ verb: "move", x: from.at.x, y: from.at.y, x2: target!.at.x, y2: target!.at.y, commodity, amount: n }); onClose(); }}>Queue move</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ExploreDialog({ gameId, from, targets, onClose, onCommand, busy, onPick }: { gameId: number; from: SectorView; targets: SectorView[]; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean; onPick: (civs: number) => void }) {
  const [targetKey, setTargetKey] = useState(targets[0] ? `${targets[0].at.x},${targets[0].at.y}` : "");
  const [civs, setCivs] = useState("20");
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
        </div>
        <DialogFooter>
          <Button variant="secondary" disabled={!(n > 0)} onClick={() => onPick(n)}>Pick on map…</Button>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !target || !(n > 0) || !est?.ok} onClick={async () => { await onCommand({ verb: "explore", x: from.at.x, y: from.at.y, x2: target!.at.x, y2: target!.at.y, amount: n }); onClose(); }}>Explore</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function DesignateDialog({ view, rules, sector: s, onClose, onCommand, busy }: { view: CountryView; rules: Rules; sector: SectorView; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean }) {
  const options = rules.sectorTypes.filter(t => !(t.flags ?? []).some(f => f === "no_designate" || f === "undesignated") && (t.minTech ?? 0) <= view.levels.tech && (!t.terrainRequired || t.terrainRequired.includes(s.terrain)));
  const [type, setType] = useState(options[0]?.id ?? "");
  const t = options.find(o => o.id === type);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Designate {s.relative.x},{s.relative.y}</DialogTitle><DialogDescription>Currently {s.designation} at {s.efficiency.toFixed(0)}%. Changing it may reset efficiency.</DialogDescription></DialogHeader>
        <label className="text-sm">Designation
          <Select value={type} onChange={e => setType(e.target.value)}>{options.map(o => <option key={o.id} value={o.id}>{o.glyph} {o.id}</option>)}</Select>
        </label>
        {t && <p className="text-xs text-muted-foreground">{t.category}{t.produces && ` · produces ${Object.keys(t.produces).join(", ")}`}{t.consumes && ` · consumes ${Object.keys(t.consumes).join(", ")}`}{t.build && ` · builds with ${Object.keys(t.build).join(", ")}`}</p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !type || type === s.designation} onClick={async () => { await onCommand({ verb: "designate", x: s.at.x, y: s.at.y, type }); onClose(); }}>Designate</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ThresholdDialog({ view, sector: s, onClose, onCommand, busy }: { view: CountryView; sector: SectorView; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void>; busy: boolean }) {
  const [commodity, setCommodity] = useState("food");
  const [amount, setAmount] = useState(s.thresholds["food"] !== undefined ? String(s.thresholds["food"]) : "");
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
        </div>
        <DialogFooter>
          {s.thresholds[commodity] !== undefined && <Button variant="danger" disabled={busy} onClick={async () => { await onCommand({ verb: "threshold", x: s.at.x, y: s.at.y, commodity, clear: true }); onClose(); }}>Clear</Button>}
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || amount === ""} onClick={async () => { await onCommand({ verb: "threshold", x: s.at.x, y: s.at.y, commodity, amount: Number(amount) }); onClose(); }}>Set</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
