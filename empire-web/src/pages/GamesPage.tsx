import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, type GameSummary } from "@/api/client";
import { useAuth } from "@/api/auth";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { ThemeToggle } from "@/components/ui/theme-toggle";

export function GamesPage() {
  const { me, logout } = useAuth();
  const [games, setGames] = useState<GameSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => api.get<GameSummary[]>("/games").then(setGames).catch(e => setError((e as Error).message)), []);
  useEffect(() => { void load(); }, [load]);

  const runUpdate = async (g: GameSummary) => {
    try { await api.post(`/admin/games/${g.id}/update`); await load(); } catch (e) { setError((e as Error).message); }
  };
  const join = async (g: GameSummary, countryId: number) => {
    try { await api.post(`/games/${g.id}/join`, { countryId }); await load(); } catch (e) { setError((e as Error).message); }
  };

  return (
    <main className="mx-auto max-w-3xl space-y-6 p-6">
      <header className="flex items-center justify-between">
        <div><h1 className="text-2xl font-semibold tracking-widest">EMPIRE</h1><p className="text-sm text-muted-foreground">{me?.name} · {me?.email}{me?.admin && " · deity"}</p></div>
        <div className="flex items-center gap-2"><ThemeToggle /><Button variant="soft" size="sm" onClick={() => void logout()}>Sign out</Button></div>
      </header>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <section className="space-y-3">
        <h2 className="font-medium">Games</h2>
        {games.length === 0 && <p className="text-sm text-muted-foreground">No games yet.</p>}
        {games.map(g => (
          <div key={g.id} className="rounded-lg border border-border bg-card p-4 text-sm">
            <div className="flex items-center justify-between">
              <div><span className="font-medium">{g.name}</span> <Badge tone="muted">{g.preset}</Badge> <Badge tone="neutral">{g.width}×{g.height}</Badge> <Badge tone="neutral">update {g.updateNumber}</Badge></div>
              <div className="flex gap-2">
                {me?.admin && <Button size="sm" variant="secondary" onClick={() => runUpdate(g)}>Run update</Button>}
                {g.myCountry != null && <Button asChild size="sm"><Link to={`/games/${g.id}`}>Play</Link></Button>}
              </div>
            </div>
            <ul className="mt-2 flex flex-wrap gap-2">
              {g.countries.map(c => (
                <li key={c.id}>
                  {c.id === g.myCountry ? <Badge tone="accent">{c.name} (you)</Badge>
                    : c.taken ? <Badge tone="neutral">{c.name}</Badge>
                    : g.myCountry == null ? <Button size="sm" variant="secondary" onClick={() => join(g, c.id)}>Join as {c.name}</Button> : <Badge tone="neutral">{c.name} (open)</Badge>}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </section>
      {me?.admin && <CreateGame onCreated={load} />}
    </main>
  );
}

function CreateGame({ onCreated }: { onCreated: () => Promise<void> }) {
  const [name, setName] = useState("New world");
  const [preset, setPreset] = useState("teaching");
  const [countries, setCountries] = useState("Rick, Sharon");
  const [seed, setSeed] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const create = async () => {
    setBusy(true); setError(null);
    try {
      await api.post("/admin/games", { name, preset, countries: countries.split(",").map(s => s.trim()).filter(Boolean), seed: seed ? Number(seed) : null });
      await onCreated();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };
  return (
    <section className="space-y-3 rounded-lg border border-border bg-card p-4 text-sm">
      <h2 className="font-medium">Create a world <span className="text-muted-foreground">(deity)</span></h2>
      <div className="grid gap-2 sm:grid-cols-2">
        <label>Name<Input value={name} onChange={e => setName(e.target.value)} /></label>
        <label>Preset<Select value={preset} onChange={e => setPreset(e.target.value)}><option value="teaching">teaching (16×16, economy only)</option><option value="sandbox">sandbox</option><option value="blitz">blitz</option><option value="classic">classic (128×64)</option></Select></label>
        <label>Countries<Input value={countries} onChange={e => setCountries(e.target.value)} placeholder="comma separated" /></label>
        <label>Seed<Input value={seed} onChange={e => setSeed(e.target.value)} placeholder="random" /></label>
      </div>
      {error && <p className="text-destructive">{error}</p>}
      <Button disabled={busy} onClick={() => void create()}>Create</Button>
    </section>
  );
}
