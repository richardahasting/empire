import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, type CommandRequest, type ConsoleReply, type Coord, type CountryView, type GameSummary, type Outcome, type Rules } from "@/api/client";
import { useAuth } from "@/api/auth";
import { HexMap, type Layer } from "@/map/HexMap";
import { Inspector } from "@/game/Inspector";
import { ConsolePanel } from "@/game/ConsolePanel";
import { Dashboard } from "@/game/Dashboard";
import { SectorMenu } from "@/game/SectorMenu";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import { ThemeToggle } from "@/components/ui/theme-toggle";

export function GamePage() {
  const { id } = useParams();
  const gameId = Number(id);
  const { me } = useAuth();
  const [game, setGame] = useState<GameSummary | null>(null);
  const [rules, setRules] = useState<Rules | null>(null);
  const [view, setView] = useState<CountryView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [selected, setSelected] = useState<Coord | null>(null);
  const [layer, setLayer] = useState<Layer>("designation");
  const [stock, setStock] = useState("food");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [g, r, v] = await Promise.all([api.get<GameSummary>(`/games/${gameId}`), api.get<Rules>(`/games/${gameId}/rules`), api.get<CountryView>(`/games/${gameId}/view`)]);
      setGame(g); setRules(r); setView(v); setError(null);
    } catch (e) { setError((e as Error).message); }
  }, [gameId]);
  useEffect(() => { void load(); }, [load]);

  const command = useCallback(async (c: CommandRequest) => {
    setBusy(true); setNotice(null);
    try {
      const o = await api.post<Outcome>(`/games/${gameId}/command`, c);
      setView(o.view);
      setNotice(o.accepted ? `${c.verb}: ok (${o.btuSpent} BTU)` : `${c.verb}: ${o.error}`);
    } catch (e) { setNotice((e as Error).message); } finally { setBusy(false); }
  }, [gameId]);

  const consoleLine = useCallback(async (line: string) => {
    const r = await api.post<ConsoleReply>(`/games/${gameId}/console`, { line });
    if (r.view) setView(r.view);
    return r;
  }, [gameId]);

  const forceUpdate = async () => {
    setBusy(true);
    try { const r = await api.post<{ updateNumber: number }>(`/admin/games/${gameId}/update`); setNotice(`update ${r.updateNumber} ran`); await load(); }
    catch (e) { setNotice((e as Error).message); } finally { setBusy(false); }
  };

  const sector = useMemo(() => view && selected ? view.sectors.find(s => s.at.x === selected.x && s.at.y === selected.y) ?? null : null, [view, selected]);

  if (error) return <main className="p-6 text-sm text-destructive">{error} · <Link className="underline" to="/games">back</Link></main>;
  if (!game || !rules || !view) return <main className="p-6 text-sm">Loading…</main>;

  return (
    <main className="flex h-full flex-col gap-3 p-3">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-3"><Link to="/games" className="text-sm underline">games</Link><h1 className="font-semibold tracking-widest">EMPIRE</h1></div>
        <div className="flex items-center gap-2 text-sm">
          <label className="flex items-center gap-1">layer
            <Select value={layer} onChange={e => setLayer(e.target.value as Layer)} className="w-36">
              <option value="ownership">ownership</option><option value="designation">designation</option><option value="efficiency">efficiency</option><option value="mobility">mobility</option><option value="stock">stock</option>
            </Select>
          </label>
          {layer === "stock" && <Select value={stock} onChange={e => setStock(e.target.value)} className="w-28">{view.commodityIds.map(c => <option key={c}>{c}</option>)}</Select>}
          {view.inSanctuary && <Button size="sm" disabled={busy} onClick={() => command({ verb: "break_sanctuary" })}>Break sanctuary</Button>}
          {me?.admin && <Button size="sm" variant="secondary" disabled={busy} onClick={() => void forceUpdate()}>Run update</Button>}
          <Button size="sm" variant="ghost" onClick={() => void load()}>Refresh</Button>
          <ThemeToggle />
        </div>
      </header>
      <Dashboard view={view} game={game} />
      {notice && <p className="text-xs text-muted-foreground">{notice}</p>}
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 lg:grid-cols-[1fr_22rem]">
        <SectorMenu gameId={gameId} view={view} rules={rules} sector={sector} onCommand={command} busy={busy}>
          <div className="min-h-[24rem]"><HexMap view={view} rules={rules} width={game.width} height={game.height} layer={layer} stockCommodity={stock} selected={selected} onSelect={setSelected} /></div>
        </SectorMenu>
        <aside className="flex min-h-0 flex-col gap-3">
          <div className="max-h-[50%] overflow-auto rounded-lg border border-border bg-card p-3"><Inspector sector={sector} view={view} rules={rules} onCommand={command} busy={busy} /></div>
          <div className="min-h-0 flex-1"><ConsolePanel onLine={consoleLine} /></div>
        </aside>
      </div>
    </main>
  );
}
