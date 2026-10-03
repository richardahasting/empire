import { useState } from "react";
import type { CommandRequest, CountryView, LotView, MarketRules } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

const rel = (c: { x: number; y: number }) => `${c.x},${c.y}`;
const money = (v: number) => `$${v.toFixed(2)}`;

/**
 * The commodity market (issue #141; the original's market, sell, buy and reset). Goods leave a harbour or warehouse when
 * they are listed; a lot sells to its high bidder when its time is up, and the goods arrive in the buyer's harbour or
 * warehouse. Every lot is public.
 */
export function Market({ view, rules, busy, onCommand }: { view: CountryView; rules: MarketRules; busy: boolean; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [dialog, setDialog] = useState<{ kind: "buy" | "lower"; lot: LotView } | { kind: "sell" } | null>(null);
  const lots = view.market ?? [];
  const quays = view.sectors.filter(s => s.full && s.owner === view.countryId && !!s.designation && rules.sectorTypes.includes(s.designation) && s.efficiency >= rules.minEfficiency);
  return (
    <div className="space-y-2 text-xs">
      <div className="flex items-center gap-2">
        <Button size="sm" variant="secondary" disabled={busy || quays.length === 0} onClick={() => setDialog({ kind: "sell" })}>Sell…</Button>
        {quays.length === 0 && <span className="text-muted-foreground">Goods are bought and sold through a {rules.sectorTypes.join(" or ")} at {rules.minEfficiency}% or better.</span>}
      </div>
      {lots.length === 0 && <p className="text-muted-foreground">Nothing on the market.</p>}
      {lots.map(l => (
        <div key={l.id} className="rounded-md border border-border p-2">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <span className="font-mono">lot {l.id}</span>
            <span className="font-medium">{l.amount.toFixed(0)} {l.commodity}</span>
            <span className="text-muted-foreground">
              {money(l.price)} a unit · {l.seller}{l.bidder ? ` · high bid ${l.bidder}, sells in ${l.updatesLeft}` : " · no bids yet"}
              {l.yours && l.fromRelative ? ` · yours, from ${rel(l.fromRelative)}` : ""}{l.yourBid && l.destRelative ? ` · your bid, to ${rel(l.destRelative)}` : ""}
            </span>
          </div>
          <div className="mt-1 flex flex-wrap gap-1">
            {!l.yours && <Button size="sm" variant="secondary" disabled={busy || quays.length === 0} onClick={() => setDialog({ kind: "buy", lot: l })}>Bid…</Button>}
            {l.yours && !l.bidder && <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDialog({ kind: "lower", lot: l })}>Lower price…</Button>}
            {l.yours && !l.bidder && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void onCommand({ verb: "reset_lot", lot: l.id, price: 0 })}>Withdraw</Button>}
          </div>
        </div>
      ))}
      {dialog?.kind === "sell" && <SellDialog rules={rules} quays={quays} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog?.kind === "buy" && <BidDialog lot={dialog.lot} rules={rules} quays={quays} view={view} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
      {dialog?.kind === "lower" && <LowerDialog lot={dialog.lot} busy={busy} onClose={() => setDialog(null)} onCommand={onCommand} />}
    </div>
  );
}

type Quays = CountryView["sectors"];

function SellDialog({ rules, quays, busy, onClose, onCommand }: { rules: MarketRules; quays: Quays; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [from, setFrom] = useState(quays[0] ? rel(quays[0].relative) : "");
  const sector = quays.find(s => rel(s.relative) === from);
  const goods = sector ? Object.entries(sector.stock).filter(([c, q]) => q >= 1 && !rules.unsellable.includes(c)).map(([c]) => c) : [];
  const [commodity, setCommodity] = useState(goods[0] ?? "");
  const [amount, setAmount] = useState("");
  const [price, setPrice] = useState("");
  const have = sector ? Math.floor(sector.stock[commodity] ?? 0) : 0;
  const n = Number(amount), p = Number(price);
  const ok = !!sector && !!commodity && amount !== "" && Number.isFinite(n) && n !== 0 && p > 0 && p <= rules.maxPrice;
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Sell on the market</DialogTitle>
          <DialogDescription>The goods leave the sector now and wait on the market. If anybody bids, the lot sells to the highest after {rules.delayUpdates} updates; nobody bids, it stays until you withdraw it.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label className="grid gap-1">From
            <Select value={from} onChange={e => { setFrom(e.target.value); setCommodity(""); }}>
              {quays.map(s => <option key={rel(s.relative)} value={rel(s.relative)}>{rel(s.relative)} {s.designation}</option>)}
            </Select>
          </label>
          <label className="grid gap-1">What
            <Select value={commodity} onChange={e => setCommodity(e.target.value)}>
              <option value="">—</option>
              {goods.map(c => <option key={c} value={c}>{c} ({Math.floor(sector?.stock[c] ?? 0)})</option>)}
            </Select>
          </label>
          <label className="grid gap-1">How many (negative: all but that many)<Input value={amount} onChange={e => setAmount(e.target.value)} inputMode="numeric" placeholder={String(have)} /></label>
          <label className="grid gap-1">Price a unit (at most {money(rules.maxPrice)})<Input value={price} onChange={e => setPrice(e.target.value)} inputMode="decimal" /></label>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !ok} onClick={async () => { if (!sector) return; await onCommand({ verb: "sell", x: sector.at.x, y: sector.at.y, commodity, amount: n, price: p }); onClose(); }}>List it</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function BidDialog({ lot, rules, quays, view, busy, onClose, onCommand }: { lot: LotView; rules: MarketRules; quays: Quays; view: CountryView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const least = lot.price + rules.minRaise;
  const [price, setPrice] = useState(least.toFixed(2));
  const [to, setTo] = useState(quays[0] ? rel(quays[0].relative) : "");
  const dest = quays.find(s => rel(s.relative) === to);
  const p = Number(price);
  const cost = p * lot.amount * rules.buyTax;
  const ok = !!dest && p >= least - 1e-9 && cost <= view.cash;
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Bid on lot {lot.id}: {lot.amount.toFixed(0)} {lot.commodity}</DialogTitle>
          <DialogDescription>A bid is a unit's price and must be at least {money(least)}. You pay only if it sells to you; it goes to the harbour or warehouse you name.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label className="grid gap-1">Price a unit<Input value={price} onChange={e => setPrice(e.target.value)} inputMode="decimal" autoFocus /></label>
          <label className="grid gap-1">Deliver to
            <Select value={to} onChange={e => setTo(e.target.value)}>
              {quays.map(s => <option key={rel(s.relative)} value={rel(s.relative)}>{rel(s.relative)} {s.designation}</option>)}
            </Select>
          </label>
          <p className="text-xs text-muted-foreground">{Number.isFinite(cost) ? `${money(cost)} if it sells to you; you have ${money(view.cash)}` : ""}</p>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !ok} onClick={async () => { if (!dest) return; await onCommand({ verb: "buy", lot: lot.id, price: p, x: dest.at.x, y: dest.at.y }); onClose(); }}>Bid</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function LowerDialog({ lot, busy, onClose, onCommand }: { lot: LotView; busy: boolean; onClose: () => void; onCommand: (c: CommandRequest) => Promise<void> }) {
  const [price, setPrice] = useState("");
  const p = Number(price);
  const ok = price !== "" && p > 0 && p < lot.price;
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Lower lot {lot.id}'s price</DialogTitle>
          <DialogDescription>Only while nobody has bid. It is {money(lot.price)} a unit now; a lower price starts its time again.</DialogDescription></DialogHeader>
        <label className="grid gap-1 text-sm">New price a unit<Input value={price} onChange={e => setPrice(e.target.value)} inputMode="decimal" autoFocus /></label>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !ok} onClick={async () => { await onCommand({ verb: "reset_lot", lot: lot.id, price: p }); onClose(); }}>Lower it</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
