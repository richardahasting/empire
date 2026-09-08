import { useEffect, useState } from "react";
import type { CountryView, GameSummary, Projection } from "@/api/client";
import { Badge } from "@/components/ui/badge";

/** Nation dashboard: treasury, BTUs, levels, the countdown, and what the next update will do to you. */
export function Dashboard({ view, game, projection }: { view: CountryView; game: GameSummary; projection: Projection | null }) {
  const owned = view.sectors.filter(s => s.full);
  const civ = owned.reduce((a, s) => a + (s.stock["civ"] ?? 0), 0);
  const food = owned.reduce((a, s) => a + (s.stock["food"] ?? 0), 0);
  const eff = owned.reduce((a, s) => a + s.efficiency, 0);
  const held = owned.reduce((a, s) => a + Object.values(s.held).reduce((x, y) => x + y, 0), 0);
  const p = projection;
  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-4 lg:grid-cols-6">
      <Stat label="country" value={view.name} />
      <Stat label="update" value={String(view.updateNumber)} sub={<Countdown game={game} />} />
      <Stat label="treasury" value={"$" + view.cash.toFixed(0)} sub={p && delta(p.cashAfter - p.cashNow, "$")} />
      <Stat label="BTUs" value={view.btu.toFixed(0)} sub={p && delta(p.btuAfter - p.btuNow, "")} />
      <Stat label="civilians" value={civ.toFixed(0)} sub={p && delta(p.civAfter - p.civNow, "")} />
      <Stat label="food" value={food.toFixed(0)} sub={p && delta(p.foodAfter - p.foodNow, "")} />
      <Stat label="sectors" value={String(owned.length)} />
      <Stat label="efficiency" value={eff.toFixed(0)} />
      <Stat label="tech" value={view.levels.tech.toFixed(1)} />
      <Stat label="research" value={view.levels.research.toFixed(1)} />
      <Stat label="education" value={view.levels.education.toFixed(1)} />
      <Stat label="happiness" value={view.levels.happiness.toFixed(1)} />
      <div className="col-span-2 flex flex-wrap gap-1 sm:col-span-4 lg:col-span-6">
        <Badge tone="muted">{game.name} · {game.preset} · {game.status}</Badge>
        {view.inSanctuary && <Badge tone="accent">in sanctuary</Badge>}
        {view.bankrupt && <Badge tone="signal">bankrupt</Badge>}
        {held > 0 && <Badge tone="neutral">{held.toFixed(0)} units in transit</Badge>}
        {p && p.starvingSectors > 0 && <Badge tone="signal">next update: starvation in {p.starvingSectors} sector{p.starvingSectors === 1 ? "" : "s"}</Badge>}
        {p && p.spoilingSectors > 0 && <Badge tone="signal">next update: spoilage in {p.spoilingSectors} sector{p.spoilingSectors === 1 ? "" : "s"}</Badge>}
        {p && p.flowsHeld > 0 && <Badge tone="neutral">next update: {p.flowsHeld} shipment{p.flowsHeld === 1 ? "" : "s"} will stall</Badge>}
      </div>
    </div>
  );
}

function delta(d: number, unit: string) {
  const sign = d > 0.5 ? "+" : d < -0.5 ? "−" : "±";
  const cls = d > 0.5 ? "text-muted-foreground" : d < -0.5 ? "text-destructive" : "text-muted-foreground";
  return <span className={cls}>{sign}{unit}{Math.abs(d).toFixed(0)} next</span>;
}

function Stat({ label, value, sub }: { label: string; value: string; sub?: React.ReactNode }) {
  return <div><div className="text-xs text-muted-foreground">{label}</div><div className="tabular-nums font-medium">{value}</div>{sub && <div className="text-xs tabular-nums">{sub}</div>}</div>;
}

/** "next in 12m 03s", "manual", or "paused" — ticks once a second from the server's timestamp. */
export function Countdown({ game }: { game: GameSummary }) {
  const [, tick] = useState(0);
  useEffect(() => { const t = setInterval(() => tick(n => n + 1), 1000); return () => clearInterval(t); }, []);
  if (game.status !== "running") return <span className="text-muted-foreground">{game.status}</span>;
  if (!game.intervalSeconds || !game.nextUpdateAt) return <span className="text-muted-foreground">manual updates</span>;
  const s = Math.max(0, Math.floor((new Date(game.nextUpdateAt).getTime() - Date.now()) / 1000));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return <span className="text-muted-foreground">next in {h > 0 ? `${h}h ` : ""}{String(m).padStart(2, "0")}m {String(sec).padStart(2, "0")}s</span>;
}
