import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, type CountryView, type SectorView } from "@/api/client";
import { useAuth } from "@/api/auth";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { FieldLabel } from "@/components/ui/tooltip";

interface Roster { countryId: number; name: string; controller: string; taken: boolean; deity: boolean }
interface Edit { id: number; update_number: number; target: string; changes: string; account_id: number | null; at: string }

const num = (s: string) => (s.trim() === "" ? null : Number(s));

/**
 * POGO's screen (issue #128). The deity looks at a game either through their own country — the whole
 * map, in absolute coordinates — or through any player's, fog and all, which is the only way to
 * answer "why can they not see that" without guessing.
 *
 * Deliberately a table rather than the game's canvas map: this is a tool for finding one sector and
 * changing it, and a list you can filter beats a picture you have to hunt across.
 */
export function PogoPage() {
  const { id } = useParams();
  const { me } = useAuth();
  const [roster, setRoster] = useState<Roster[]>([]);
  const [as, setAs] = useState<number | null>(null);        // null = POGO, the unfogged view
  const [view, setView] = useState<CountryView | null>(null);
  const [edits, setEdits] = useState<Edit[]>([]);
  const [filter, setFilter] = useState("");
  const [picked, setPicked] = useState<SectorView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [r, v, e] = await Promise.all([
        api.get<Roster[]>(`/admin/games/${id}/roster`),
        api.get<CountryView>(`/admin/games/${id}/view${as == null ? "" : `?as=${as}`}`),
        api.get<Edit[]>(`/admin/games/${id}/edits`),
      ]);
      setRoster(r); setView(v); setEdits(e);
    } catch (err) { setError((err as Error).message); }
  }, [id, as]);

  useEffect(() => { void load(); }, [load]);

  const deity = roster.find(r => r.deity);
  const sectors = useMemo(() => {
    if (!view) return [];
    const f = filter.trim().toLowerCase();
    const list = view.sectors.filter(s => !f
      || `${s.at.x},${s.at.y}`.includes(f)
      || (s.designation ?? "").toLowerCase().includes(f)
      || s.terrain.toLowerCase().includes(f)
      || (s.ownerName ?? "").toLowerCase().includes(f));
    return list.slice(0, 400);
  }, [view, filter]);

  if (!me?.admin) return <main className="p-6 text-sm">This is the deity's screen. <Link className="underline" to="/games">Games</Link></main>;

  return (
    <main className="mx-auto max-w-6xl space-y-5 p-6">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-widest">POGO</h1>
          <p className="text-sm text-muted-foreground">
            game {id}{view && <> · update {view.updateNumber} · {view.width}×{view.height}</>}
            {deity ? <> · deity country {deity.countryId} at 0,0</> : <> · this game predates POGO</>}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button asChild variant="soft" size="sm"><Link to="/help/pogo">What POGO is</Link></Button>
          <ThemeToggle />
          <Button asChild variant="soft" size="sm"><Link to="/games">Games</Link></Button>
        </div>
      </header>

      {error && <p className="text-sm text-destructive">{error}</p>}
      {note && <p className="text-sm text-muted-foreground">{note}</p>}

      <section className="space-y-2 rounded-lg border border-border bg-card p-4 text-sm">
        <FieldLabel label="Looking through" hint="POGO sees every sector, in the map's own coordinates. Any other country shows the world as that player actually sees it — their fog, their map memory, their contacts — which is how you find out why they cannot see something." />
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant={as == null ? "primary" : "soft"} onClick={() => { setAs(null); setPicked(null); }}>
            POGO — everything
          </Button>
          {roster.filter(r => !r.deity).map(r => (
            <Button key={r.countryId} size="sm" variant={as === r.countryId ? "primary" : "soft"}
                    onClick={() => { setAs(r.countryId); setPicked(null); }}>
              {r.name}{r.controller === "agent" ? " ·bot" : r.taken ? "" : " ·open"}
            </Button>
          ))}
        </div>
        {view && (
          <p className="text-xs text-muted-foreground">
            {as == null
              ? `${view.sectors.length} sectors — the whole map, absolute coordinates`
              : `${view.sectors.length} of ${view.width * view.height} sectors visible to ${view.name}; coordinates are relative to their capital`}
          </p>
        )}
      </section>

      <div className="grid gap-5 lg:grid-cols-[1fr_20rem]">
        <section className="min-w-0 space-y-2 rounded-lg border border-border bg-card p-4 text-sm">
          <div className="flex items-center justify-between gap-2">
            <h2 className="font-medium">Sectors</h2>
            <Input value={filter} onChange={e => setFilter(e.target.value)} placeholder="filter: 3,4 · mine · mountain · name" className="max-w-xs" />
          </div>
          <div className="max-h-[26rem] overflow-auto">
            <table className="w-full text-xs">
              <thead className="sticky top-0 bg-card">
                <tr className="text-left text-muted-foreground">
                  <th className="py-1 pr-2">at</th><th className="pr-2">terrain</th><th className="pr-2">owner</th>
                  <th className="pr-2">designation</th><th className="pr-2 text-right">eff</th><th className="pr-2 text-right">mob</th>
                </tr>
              </thead>
              <tbody>
                {sectors.map(s => (
                  <tr key={`${s.at.x},${s.at.y}`}
                      onClick={() => setPicked(s)}
                      className={`cursor-pointer border-t border-border hover:bg-muted ${picked && picked.at.x === s.at.x && picked.at.y === s.at.y ? "bg-muted" : ""}`}>
                    <td className="py-1 pr-2 font-medium">{s.at.x},{s.at.y}</td>
                    <td className="pr-2">{s.terrain}</td>
                    <td className="pr-2">{s.ownerName ?? (s.owner >= 0 ? s.owner : "—")}</td>
                    <td className="pr-2">{s.designation ?? "—"}</td>
                    <td className="pr-2 text-right">{Math.round(s.efficiency)}</td>
                    <td className="pr-2 text-right">{Math.round(s.mobility)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {view && view.sectors.length > sectors.length && (
              <p className="pt-2 text-xs text-muted-foreground">showing {sectors.length} of {view.sectors.length} — filter to narrow</p>
            )}
          </div>
        </section>

        <div className="space-y-5">
          {picked && <SectorEditor gameId={Number(id)} sector={picked} absolute={as == null}
                                   onDone={m => { setNote(m); setPicked(null); void load(); }} onError={setError} />}
          <CountryEditor gameId={Number(id)} roster={roster} onDone={m => { setNote(m); void load(); }} onError={setError} />
        </div>
      </div>

      <section className="space-y-2 rounded-lg border border-border bg-card p-4 text-sm">
        <FieldLabel label="Edits" hint="Every hand edit, because each one is a write outside the update and costs the game its conservation and determinism guarantees. When a world will not reproduce, this is the answer." />
        {edits.length === 0 && <p className="text-xs text-muted-foreground">Nothing has been changed by hand in this game.</p>}
        {edits.length > 0 && (
          <ul className="max-h-48 space-y-1 overflow-auto text-xs">
            {edits.map(e => (
              <li key={e.id} className="border-t border-border py-1">
                <span className="text-muted-foreground">update {e.update_number} · </span>
                <span className="font-medium">{e.target}</span>
                <span className="text-muted-foreground"> — {e.changes}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}

/** Editing needs absolute coordinates; a player's view reports relative ones, so it is read-only there. */
function SectorEditor({ gameId, sector, absolute, onDone, onError }:
                      { gameId: number; sector: SectorView; absolute: boolean; onDone: (m: string) => void; onError: (m: string) => void }) {
  const [efficiency, setEfficiency] = useState("");
  const [mobility, setMobility] = useState("");
  const [designation, setDesignation] = useState("");
  const [owner, setOwner] = useState("");
  const [minerals, setMinerals] = useState("");
  const [fertility, setFertility] = useState("");
  const [busy, setBusy] = useState(false);

  const at = sector.at;
  const save = async () => {
    setBusy(true);
    try {
      await api.post(`/admin/games/${gameId}/sectors/${at.x}/${at.y}`, {
        efficiency: num(efficiency), mobility: num(mobility),
        designation: designation.trim() || null, owner: num(owner),
        minerals: num(minerals), fertility: num(fertility),
      });
      onDone(`${at.x},${at.y} changed.`);
      setEfficiency(""); setMobility(""); setDesignation(""); setOwner(""); setMinerals(""); setFertility("");
    } catch (e) { onError((e as Error).message); } finally { setBusy(false); }
  };

  return (
    <section className="space-y-3 rounded-lg border border-border bg-card p-4 text-sm">
      <h2 className="font-medium">Sector {at.x},{at.y}</h2>
      <p className="text-xs text-muted-foreground">
        {sector.terrain}{sector.designation ? ` · ${sector.designation}` : ""} · eff {Math.round(sector.efficiency)} · mob {Math.round(sector.mobility)}
        {sector.resources && <> · fert {sector.resources.fertility} · min {sector.resources.minerals} · gold {sector.resources.gold} · oil {sector.resources.oil} · uran {sector.resources.uranium}</>}
      </p>
      {!absolute ? (
        <p className="text-xs text-muted-foreground">
          These are {sector.at.x},{sector.at.y} in this player's own coordinates. Switch to POGO to edit —
          a sector is changed by its place on the map, not by where it sits relative to someone.
        </p>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-2">
            <label className="space-y-1"><span className="text-xs">efficiency</span><Input value={efficiency} onChange={e => setEfficiency(e.target.value)} placeholder={String(Math.round(sector.efficiency))} inputMode="numeric" /></label>
            <label className="space-y-1"><span className="text-xs">mobility</span><Input value={mobility} onChange={e => setMobility(e.target.value)} placeholder={String(Math.round(sector.mobility))} inputMode="numeric" /></label>
            <label className="space-y-1"><span className="text-xs">designation</span><Input value={designation} onChange={e => setDesignation(e.target.value)} placeholder={sector.designation ?? "none"} /></label>
            <label className="space-y-1"><span className="text-xs">owner (id, -1 none)</span><Input value={owner} onChange={e => setOwner(e.target.value)} placeholder={String(sector.owner)} inputMode="numeric" /></label>
            <label className="space-y-1"><span className="text-xs">fertility</span><Input value={fertility} onChange={e => setFertility(e.target.value)} placeholder={String(sector.resources?.fertility ?? "")} inputMode="numeric" /></label>
            <label className="space-y-1"><span className="text-xs">minerals</span><Input value={minerals} onChange={e => setMinerals(e.target.value)} placeholder={String(sector.resources?.minerals ?? "")} inputMode="numeric" /></label>
          </div>
          <p className="text-xs text-muted-foreground">Blank fields are left alone. Every change is logged.</p>
          <Button size="sm" variant="danger" disabled={busy} onClick={() => void save()}>{busy ? "Saving…" : "Change this sector"}</Button>
        </>
      )}
    </section>
  );
}

function CountryEditor({ gameId, roster, onDone, onError }:
                       { gameId: number; roster: Roster[]; onDone: (m: string) => void; onError: (m: string) => void }) {
  const [countryId, setCountryId] = useState<number | null>(null);
  const [cash, setCash] = useState("");
  const [btu, setBtu] = useState("");
  const [tech, setTech] = useState("");
  const [busy, setBusy] = useState(false);
  const chosen = roster.find(r => r.countryId === countryId);

  const save = async () => {
    if (countryId == null) return;
    setBusy(true);
    try {
      await api.post(`/admin/games/${gameId}/countries/${countryId}/edit`, { cash: num(cash), btu: num(btu), tech: num(tech) });
      onDone(`${chosen?.name} changed.`);
      setCash(""); setBtu(""); setTech("");
    } catch (e) { onError((e as Error).message); } finally { setBusy(false); }
  };

  const reissue = async () => {
    if (countryId == null) return;
    try {
      const r = await api.post<{ token: string }>(`/admin/games/${gameId}/countries/${countryId}/token`);
      window.prompt(`New token for ${chosen?.name}. It is shown once — copy it now.`, r.token);
      onDone(`${chosen?.name} has a new token.`);
    } catch (e) { onError((e as Error).message); }
  };

  return (
    <section className="space-y-3 rounded-lg border border-border bg-card p-4 text-sm">
      <h2 className="font-medium">Country</h2>
      <Select value={countryId == null ? "" : String(countryId)} onChange={e => setCountryId(e.target.value === "" ? null : Number(e.target.value))}>
        <option value="">choose a country…</option>
        {roster.map(r => <option key={r.countryId} value={r.countryId}>{r.name}{r.deity ? " (deity)" : r.controller === "agent" ? " (bot)" : ""}</option>)}
      </Select>
      {countryId != null && (
        <>
          <div className="grid grid-cols-3 gap-2">
            <label className="space-y-1"><span className="text-xs">cash</span><Input value={cash} onChange={e => setCash(e.target.value)} inputMode="numeric" /></label>
            <label className="space-y-1"><span className="text-xs">BTUs</span><Input value={btu} onChange={e => setBtu(e.target.value)} inputMode="numeric" /></label>
            <label className="space-y-1"><span className="text-xs">tech</span><Input value={tech} onChange={e => setTech(e.target.value)} inputMode="numeric" /></label>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="danger" disabled={busy} onClick={() => void save()}>{busy ? "Saving…" : "Change"}</Button>
            {chosen?.controller === "agent" && (
              <Button size="sm" variant="soft" onClick={() => void reissue()} title="mint this bot a replacement token">New token</Button>
            )}
          </div>
        </>
      )}
    </section>
  );
}
