import type { CountryView, GameSummary } from "@/api/client";
import { Badge } from "@/components/ui/badge";

/** Nation dashboard, M1 edition: treasury, BTUs, levels, update number. Budget projection is M2. */
export function Dashboard({ view, game }: { view: CountryView; game: GameSummary }) {
  const owned = view.sectors.filter(s => s.full);
  const civ = owned.reduce((a, s) => a + (s.stock["civ"] ?? 0), 0);
  const food = owned.reduce((a, s) => a + (s.stock["food"] ?? 0), 0);
  const eff = owned.reduce((a, s) => a + s.efficiency, 0);
  const held = owned.reduce((a, s) => a + Object.values(s.held).reduce((x, y) => x + y, 0), 0);
  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-4">
      <Stat label="country" value={view.name} />
      <Stat label="update" value={String(view.updateNumber)} />
      <Stat label="treasury" value={"$" + view.cash.toFixed(0)} />
      <Stat label="BTUs" value={view.btu.toFixed(0)} />
      <Stat label="sectors" value={String(owned.length)} />
      <Stat label="civilians" value={civ.toFixed(0)} />
      <Stat label="food" value={food.toFixed(0)} />
      <Stat label="efficiency" value={eff.toFixed(0)} />
      <Stat label="tech" value={view.levels.tech.toFixed(1)} />
      <Stat label="research" value={view.levels.research.toFixed(1)} />
      <Stat label="education" value={view.levels.education.toFixed(1)} />
      <Stat label="happiness" value={view.levels.happiness.toFixed(1)} />
      <div className="col-span-2 flex flex-wrap gap-1 sm:col-span-4">
        <Badge tone="muted">{game.name} · {game.preset} · {game.status}</Badge>
        {view.inSanctuary && <Badge tone="accent">in sanctuary</Badge>}
        {view.bankrupt && <Badge tone="signal">bankrupt</Badge>}
        {held > 0 && <Badge tone="neutral">{held.toFixed(0)} units in transit</Badge>}
      </div>
    </div>
  );
}
function Stat({ label, value }: { label: string; value: string }) {
  return <div><div className="text-xs text-muted-foreground">{label}</div><div className="tabular-nums font-medium">{value}</div></div>;
}
