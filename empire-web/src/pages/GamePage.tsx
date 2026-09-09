import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, macrosApi, type CommandRequest, type ConsoleReply, type Coord, type CountryView, type GameSummary, type LastUpdate, type Macro, type MacroStep, type Outcome, type Projection, type Rules } from "@/api/client";
import { MacrosDialog, RecordMacroDialog, RunMacroDialog, stepFromCommand } from "@/game/Macros";
import { COMMODITY_HUES } from "@/map/palette";
import { useAuth } from "@/api/auth";
import { HexMap, type Layer } from "@/map/HexMap";
import { Inspector } from "@/game/Inspector";
import { ConsolePanel } from "@/game/ConsolePanel";
import { Dashboard } from "@/game/Dashboard";
import { SectorMenu, supplyFromCapital, type PickSpec } from "@/game/SectorMenu";
import { estimate, type Estimate } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { ThemeToggle } from "@/components/ui/theme-toggle";

export function GamePage() {
  const { id } = useParams();
  const gameId = Number(id);
  const { me } = useAuth();
  const [game, setGame] = useState<GameSummary | null>(null);
  const [rules, setRules] = useState<Rules | null>(null);
  const [view, setView] = useState<CountryView | null>(null);
  const [projection, setProjection] = useState<Projection | null>(null);
  const [last, setLast] = useState<LastUpdate | null>(null);
  const [showFlows, setShowFlows] = useState(true);
  const [flowT, setFlowT] = useState(0);
  const [playing, setPlaying] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [selected, setSelected] = useState<Coord | null>(null);
  const [layer, setLayer] = useState<Layer>("designation");
  const [stock, setStock] = useState("food");
  const [busy, setBusy] = useState(false);
  // macros (issue #47): per account; recording captures panel commands until stopped
  const [macros, setMacros] = useState<Macro[]>([]);
  const [recording, setRecording] = useState<{ slot: number; name: string; steps: MacroStep[] } | null>(null);
  const [macroDialog, setMacroDialog] = useState<null | "record" | "run" | "list">(null);
  useEffect(() => { macrosApi.list().then(setMacros).catch(() => {}); }, []);

  const load = useCallback(async () => {
    try {
      const [g, r, v] = await Promise.all([api.get<GameSummary>(`/games/${gameId}`), api.get<Rules>(`/games/${gameId}/rules`), api.get<CountryView>(`/games/${gameId}/view`)]);
      setGame(g); setRules(r); setView(v); setError(null);
    } catch (e) { setError((e as Error).message); }
  }, [gameId]);
  useEffect(() => { void load(); }, [load]);
  // last update's flows, refetched when the update number changes
  useEffect(() => {
    if (!view) return;
    let live = true;
    api.get<LastUpdate>(`/games/${gameId}/last-update`).then(u => { if (live) setLast(u); }).catch(() => {});
    return () => { live = false; };
  }, [gameId, view?.updateNumber]);   // eslint-disable-line react-hooks/exhaustive-deps
  // the scrubber runs on its own clock: one pass through the update every 4 s
  useEffect(() => {
    if (!playing || !showFlows) return;
    let raf = 0; let t0 = performance.now();
    const step = (now: number) => { setFlowT(t => (t + (now - t0) / 4000) % 1); t0 = now; raf = requestAnimationFrame(step); };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [playing, showFlows]);
  // projection: recomputed whenever the view changes (your own commands change the outcome)
  useEffect(() => {
    if (!view) return;
    let live = true;
    api.get<Projection>(`/games/${gameId}/projection`).then(p => { if (live) setProjection(p); }).catch(() => { if (live) setProjection(null); });
    return () => { live = false; };
  }, [gameId, view]);
  // poll: when an update has run (scheduled or by the deity), reload everything
  useEffect(() => {
    const t = setInterval(async () => {
      try {
        const g = await api.get<GameSummary>(`/games/${gameId}`);
        setGame(prev => {
          if (prev && g.updateNumber !== prev.updateNumber) { setNotice(`update ${g.updateNumber} ran`); void load(); }
          return g;
        });
      } catch { /* offline for a moment */ }
    }, 15000);
    return () => clearInterval(t);
  }, [gameId, load]);

  const command = useCallback(async (c: CommandRequest) => {
    setBusy(true); setNotice(null);
    try {
      const o = await api.post<Outcome>(`/games/${gameId}/command`, c);
      setView(o.view);
      setNotice(o.accepted ? `${c.verb}: ${o.info ?? "ok"} (${o.btuSpent} BTU)` : `${c.verb}: ${o.error}`);
      if (o.accepted && recording && view) { const step = stepFromCommand(c, view); if (step) setRecording(r => r ? { ...r, steps: [...r.steps, step] } : r); }
    } catch (e) { setNotice((e as Error).message); } finally { setBusy(false); }
  }, [gameId, recording, view]);

  const saveMacro = useCallback(async (m: Macro) => {
    try { const saved = await macrosApi.save(m); setMacros(ms => [...ms.filter(x => x.slot !== saved.slot), saved].sort((a, b) => a.slot - b.slot)); setNotice(`macro ${saved.slot === 10 ? 0 : saved.slot} "${saved.name}" saved: ${saved.steps.length} step${saved.steps.length === 1 ? "" : "s"}`); }
    catch (e) { setNotice((e as Error).message); }
  }, []);
  const deleteMacro = useCallback(async (slot: number) => {
    try { await macrosApi.remove(slot); setMacros(ms => ms.filter(x => x.slot !== slot)); } catch (e) { setNotice((e as Error).message); }
  }, []);
  const stopRecording = useCallback(async () => {
    if (!recording) return;
    const r = recording; setRecording(null);
    if (r.steps.length === 0) { setNotice(`macro "${r.name}" discarded: nothing was recorded`); return; }
    await saveMacro({ slot: r.slot, name: r.name, steps: r.steps });
  }, [recording, saveMacro]);
  const runMacro = useCallback(async (slot: number, at: Coord | null, scope?: string) => {
    const m = macros.find(x => x.slot === slot);
    if (!m) { setNotice(`no macro in slot ${slot === 10 ? 0 : slot}`); return; }
    setBusy(true); setNotice(null);
    try {
      const o = await macrosApi.run(gameId, slot, at, scope);
      setView(o.view);
      setNotice(o.accepted ? `macro "${m.name}": ${o.info ?? "ok"} (${o.btuSpent} BTU)` : `macro "${m.name}": ${o.error}`);
    } catch (e) { setNotice((e as Error).message); } finally { setBusy(false); }
  }, [gameId, macros]);
  // digit keys run macro slots on the selected sector, when nothing is being typed
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (t && ["INPUT", "TEXTAREA", "SELECT"].includes(t.tagName)) return;
      if (e.ctrlKey || e.metaKey || e.altKey || !/^[0-9]$/.test(e.key)) return;
      if (!selected || busy) return;
      e.preventDefault();
      void runMacro(e.key === "0" ? 10 : Number(e.key), selected);
    };
    window.addEventListener("keydown", onKey); return () => window.removeEventListener("keydown", onKey);
  }, [selected, busy, runMacro]);

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

  // ---- targeting mode: hover for estimates, click to commit, Esc to cancel ----
  const [pick, setPick] = useState<PickSpec | null>(null);
  const [hover, setHover] = useState<Coord | null>(null);
  const [est, setEst] = useState<Estimate | null>(null);
  const estCache = useMemo(() => new Map<string, Estimate>(), []);
  useEffect(() => { estCache.clear(); setEst(null); }, [pick, estCache]);
  useEffect(() => {
    if (!pick || !hover || !view) { setEst(null); return; }
    if (pick.verb === "distribute") { setEst(null); return; }
    const key = `${hover.x},${hover.y}`;
    const cached = estCache.get(key);
    if (cached) { setEst(cached); return; }
    let live = true;
    estimate(gameId, { verb: pick.verb, x: pick.from.at.x, y: pick.from.at.y, x2: hover.x, y2: hover.y, commodity: pick.verb === "move" ? pick.commodity : undefined, amount: pick.qty })
      .then(e => { estCache.set(key, e); if (live) setEst(e); })
      .catch(e => { if (live) setEst({ ok: false, error: (e as Error).message, path: [], hopCosts: [], totalMobility: 0, reach: 0, arrivesQty: 0, heldQty: 0, holdsAt: null, available: 0, sourceMobility: 0 }); });
    return () => { live = false; };
  }, [pick, hover, view, gameId, estCache]);
  useEffect(() => {
    if (!pick) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setPick(null); };
    window.addEventListener("keydown", onKey); return () => window.removeEventListener("keydown", onKey);
  }, [pick]);
  const byRel = useMemo(() => { const m = new Map<string, Coord>(); view?.sectors.forEach(s => m.set(`${s.relative.x},${s.relative.y}`, s.at)); return m; }, [view]);
  const highlightPath = useMemo(() => pick && est?.ok ? est.path.map(c => byRel.get(`${c.x},${c.y}`)).filter((c): c is Coord => !!c) : undefined, [pick, est, byRel]);
  const tooltip = useMemo(() => {
    if (!pick || !hover || !view) return null;
    const hv = view.sectors.find(s => s.at.x === hover.x && s.at.y === hover.y);
    const where = hv ? `${hv.relative.x},${hv.relative.y}` : "?";
    if (pick.verb === "distribute") {
      if (!hv || !hv.full) return `${where}\nnot yours — a centre must be a sector you own`;
      if (hv.at.x === pick.from.at.x && hv.at.y === pick.from.at.y) return `${where}\nthat is the sector itself`;
      return `${where} · ${hv.designation}\nclick: surplus from ${pick.from.relative.x},${pick.from.relative.y} goes here, shortages come from here`;
    }
    if (!est) return `${where}\nestimating…`;
    if (!est.ok) return `${where}\n${est.error ?? "no route"}`;
    // available = the sending sector's mobility (it pays the whole route, per the rules)
    const avail = est.sourceMobility;
    const hold = est.heldQty > 0 ? `\nonly ${est.arrivesQty.toFixed(0)} can move now` : "";
    return `${where} · ${est.path.length - 1} hop${est.path.length - 1 === 1 ? "" : "s"}\nmob required ${est.totalMobility.toFixed(0)} / ${avail.toFixed(0)}${hold}\nclick to ${pick.verb}`;
  }, [pick, hover, est, view]);
  const onMapSelect = useCallback((c: Coord | null) => {
    if (!pick) { setSelected(c); return; }
    if (pick.verb === "distribute") {
      const hv = c && view?.sectors.find(s => s.at.x === c.x && s.at.y === c.y);
      if (!hv || !hv.full || (hv.at.x === pick.from.at.x && hv.at.y === pick.from.at.y)) return;
      const spec = pick; setPick(null);
      void command({ verb: "distribute", x: spec.from.at.x, y: spec.from.at.y, x2: c.x, y2: c.y });
      return;
    }
    if (!c || !est?.ok || pick.qty <= 0) return;
    const spec = pick; setPick(null);
    if (spec.verb === "move") { void command({ verb: "move", x: spec.from.at.x, y: spec.from.at.y, x2: c.x, y2: c.y, commodity: spec.commodity, amount: spec.qty }); return; }
    void (async () => {
      await command({ verb: "explore", x: spec.from.at.x, y: spec.from.at.y, x2: c.x, y2: c.y, amount: spec.qty });
      if (spec.supply && view) await supplyFromCapital(view, c, command);
    })();
  }, [pick, est, command, view]);

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
              <option value="ownership">ownership</option><option value="designation">designation</option><option value="efficiency">efficiency</option><option value="mobility">mobility</option><option value="stock">stock</option><option value="roads">roads</option><option value="rail">rail</option>
            </Select>
          </label>
          {layer === "stock" && <Select value={stock} onChange={e => setStock(e.target.value)} className="w-28">{view.commodityIds.map(c => <option key={c}>{c}</option>)}</Select>}
          {view.inSanctuary && <Button size="sm" disabled={busy} onClick={() => command({ verb: "break_sanctuary" })}>Break sanctuary</Button>}
          {me?.admin && <Button size="sm" variant="secondary" disabled={busy} onClick={() => void forceUpdate()}>Run update</Button>}
          <label className="flex items-center gap-1 text-xs"><input type="checkbox" checked={showFlows} onChange={e => setShowFlows(e.target.checked)} /> flows</label>
          {showFlows && <Button size="sm" variant="ghost" onClick={() => setPlaying(p => !p)}>{playing ? "❚❚" : "▶"}</Button>}
          {showFlows && <input type="range" min={0} max={1000} value={Math.round(flowT * 1000)} onChange={e => { setPlaying(false); setFlowT(Number(e.target.value) / 1000); }} className="w-28" aria-label="scrub the last update" />}
          <Button size="sm" variant="ghost" onClick={() => void load()}>Refresh</Button>
          <ThemeToggle />
        </div>
      </header>
      <Dashboard view={view} game={game} projection={projection} />
      {notice && <p className="text-xs text-muted-foreground">{notice}</p>}
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <SectorMenu gameId={gameId} view={view} rules={rules} sector={sector} onCommand={command} busy={busy} onStartPick={setPick} history={sector ? last?.notes?.[`${sector.relative.x},${sector.relative.y}`] : undefined} historyUpdate={last?.updateNumber}
          macros={macros} recording={!!recording} onRecordMacro={() => setMacroDialog("record")} onStopRecording={() => void stopRecording()} onRunMacro={(slot, at) => void runMacro(slot, at)} onRunMacroDialog={() => setMacroDialog("run")} onOpenMacros={() => setMacroDialog("list")}>
          <div className="relative min-h-[24rem] min-w-0">
            {recording && !pick && (
              <div className="absolute left-2 top-2 z-10 flex items-center gap-2 rounded-md border border-destructive bg-popover px-2 py-1 text-xs shadow-md">
                <span className="inline-block h-2 w-2 rounded-full bg-destructive" aria-hidden />
                <span>Recording macro {recording.slot === 10 ? 0 : recording.slot} "{recording.name}" — {recording.steps.length} step{recording.steps.length === 1 ? "" : "s"}. Play as usual; the sector is left blank.</span>
                <Button size="sm" variant="secondary" onClick={() => void stopRecording()}>Stop</Button>
              </div>
            )}
            {pick && (
              <div className="absolute left-2 top-2 z-10 flex items-center gap-2 rounded-md border border-border bg-popover px-2 py-1 text-xs shadow-md">
                {pick.verb === "distribute" ? (
                  <span>Choosing the distribution centre for {pick.from.relative.x},{pick.from.relative.y} — click a sector you own</span>
                ) : (<>
                <span>{pick.verb === "move" ? "Moving" : "Exploring with"}</span>
                <Input value={String(pick.qty)} onChange={e => { const q = Math.max(0, Math.floor(Number(e.target.value) || 0)); setPick({ ...pick, qty: q }); }}
                       inputMode="numeric" className="h-7 w-20 text-xs" aria-label="quantity" />
                <span>{pick.verb === "move" ? pick.commodity : "civilians"} <span className="text-muted-foreground">(of {Math.floor(pick.from.stock[pick.commodity] ?? 0)})</span> from {pick.from.relative.x},{pick.from.relative.y} — click a destination</span>
                {pick.commodity === "civ" && pick.from.at.x === view.capital.x && pick.from.at.y === view.capital.y && (pick.from.stock["civ"] ?? 0) - pick.qty < 100 && (
                  <span className="text-destructive">leaves the capital with {Math.max(0, Math.floor((pick.from.stock["civ"] ?? 0) - pick.qty))} civilians — BTUs come from them</span>
                )}
                </>)}
                <Button size="sm" variant="ghost" onClick={() => setPick(null)}>Cancel (Esc)</Button>
              </div>
            )}
            <HexMap view={view} rules={rules} width={game.width} height={game.height} layer={layer} stockCommodity={stock} selected={selected} onSelect={onMapSelect}
                    onHover={pick ? (c) => setHover(c) : undefined} highlightPath={highlightPath} tooltip={tooltip} picking={!!pick}
                    flows={showFlows && last ? last.flows : undefined} flowT={flowT} />
            {showFlows && last && last.flows.length > 0 && (
              <div className="absolute bottom-2 left-2 z-10 flex flex-wrap items-center gap-2 rounded-md border border-border bg-popover/90 px-2 py-1 text-xs">
                <span className="text-muted-foreground">update {last.updateNumber}:</span>
                {[...new Set(last.flows.filter(f => f.qtyMoved > 0).map(f => f.commodity))].map(c => (
                  <span key={c} className="flex items-center gap-1"><span className="inline-block h-2 w-2 rounded-full" style={{ background: `oklch(0.7 0.19 ${COMMODITY_HUES[c] ?? 0})` }} />{c}</span>
                ))}
                {last.flows.some(f => !f.completed && f.qtyMoved > 0) && <span className="text-muted-foreground">· ✕ = held short</span>}
                {last.flows.filter(f => f.qtyMoved <= 0).length > 0 && <span className="text-muted-foreground">· {last.flows.filter(f => f.qtyMoved <= 0).length} wanted, nothing to send</span>}
              </div>
            )}
          </div>
        </SectorMenu>
        {macroDialog === "record" && <RecordMacroDialog macros={macros} onClose={() => setMacroDialog(null)} onStart={(slot, name) => setRecording({ slot, name, steps: [] })} />}
        {macroDialog === "run" && sector && <RunMacroDialog macros={macros} sectorLabel={`${sector.relative.x},${sector.relative.y}`} designation={sector.designation} onClose={() => setMacroDialog(null)} onRun={(slot, scope) => void runMacro(slot, sector.at, scope)} />}
        {macroDialog === "list" && <MacrosDialog macros={macros} onClose={() => setMacroDialog(null)} onSave={saveMacro} onDelete={deleteMacro} />}
        <aside className="flex min-h-0 min-w-0 flex-col gap-3">
          <div className="max-h-[50%] overflow-auto rounded-lg border border-border bg-card p-3"><Inspector sector={sector} view={view} rules={rules} onCommand={command} busy={busy} history={sector ? last?.notes?.[`${sector.relative.x},${sector.relative.y}`] : undefined} historyUpdate={last?.updateNumber} /></div>
          <div className="min-h-0 flex-1"><ConsolePanel onLine={consoleLine} /></div>
        </aside>
      </div>
    </main>
  );
}
