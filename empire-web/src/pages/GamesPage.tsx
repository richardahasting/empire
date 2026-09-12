import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, type GameSummary, type CountrySeat, type NewsItem } from "@/api/client";
import { useAuth } from "@/api/auth";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Checkbox } from "@/components/ui/checkbox";
import { Radio, RadioGroup } from "@/components/ui/radio";
import { FieldLabel } from "@/components/ui/tooltip";
import { Dialog, DialogTrigger, DialogContent, DialogHeader, DialogFooter, DialogTitle, DialogDescription } from "@/components/ui/dialog";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { Countdown } from "@/game/Dashboard";

export function GamesPage() {
  const { me, logout } = useAuth();
  const [games, setGames] = useState<GameSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => api.get<GameSummary[]>("/games").then(setGames).catch(e => setError((e as Error).message)), []);
  useEffect(() => { void load(); }, [load]);

  const setSchedule = async (g: GameSummary, interval: string) => {
    try { await api.post(`/admin/games/${g.id}/schedule`, { interval }); await load(); } catch (e) { setError((e as Error).message); }
  };
  const setStatus = async (g: GameSummary, status: string) => {
    try { await api.post(`/admin/games/${g.id}/status`, { status }); await load(); } catch (e) { setError((e as Error).message); }
  };
  const runUpdate = async (g: GameSummary) => {
    try { await api.post(`/admin/games/${g.id}/update`); await load(); } catch (e) { setError((e as Error).message); }
  };
  const seedSea = async (g: GameSummary) => {
    if (!window.confirm(`Seed fishing grounds in "${g.name}"? Ocean fertility is set from the game's seed; land is untouched.`)) return;
    try { await api.post(`/admin/games/${g.id}/sea-fertility`); await load(); } catch (e) { setError((e as Error).message); }
  };
  const reloadRules = async (g: GameSummary) => {
    if (!window.confirm(`Reload the rules of "${g.name}" from the ${g.preset} preset as shipped now? The game keeps its map and stocks; only the rules change.`)) return;
    try { await api.post(`/admin/games/${g.id}/config/refresh`); await load(); } catch (e) { setError((e as Error).message); }
  };
  const start = async (g: GameSummary) => {
    const open = g.countries.filter(c => !c.taken).length;
    if (!window.confirm(`Start "${g.name}" now with ${open} seat${open === 1 ? "" : "s"} still open? The first update will be scheduled.`)) return;
    try { await api.post(`/admin/games/${g.id}/start`); await load(); } catch (e) { setError((e as Error).message); }
  };

  return (
    <main className="mx-auto max-w-3xl space-y-6 p-6">
      <header className="flex items-center justify-between">
        <div><h1 className="text-2xl font-semibold tracking-widest">EMPIRE</h1><p className="text-sm text-muted-foreground">{me?.name} · {me?.email}{me?.admin && " · deity"}</p></div>
        <div className="flex items-center gap-2"><Button asChild variant="soft" size="sm"><Link to="/help">Guide</Link></Button><ThemeToggle /><Button variant="soft" size="sm" onClick={() => void logout()}>Sign out</Button></div>
      </header>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <section className="space-y-3">
        <h2 className="font-medium">Games</h2>
        {games.length === 0 && <p className="text-sm text-muted-foreground">No games yet.</p>}
        {games.map(g => (
          <div key={g.id} className="rounded-lg border border-border bg-card p-4 text-sm">
            <div className="flex items-center justify-between">
              <div><span className="font-medium">{g.name}</span> <Badge tone="muted">{g.preset}</Badge> <Badge tone="neutral">{g.width}×{g.height}</Badge> <Badge tone="neutral">update {g.updateNumber}</Badge> <Badge tone={g.status === "running" ? "muted" : "signal"}>{g.status}</Badge> <span className="text-xs"><Countdown game={g} /></span></div>
              <div className="flex items-center gap-2">
                {me?.admin && (
                  <Select value={intervalLabel(g.intervalSeconds)} onChange={e => setSchedule(g, e.target.value)} className="w-32" aria-label="update interval">
                    <option value="0">manual</option><option value="5m">every 5m</option><option value="15m">every 15m</option><option value="1h">every hour</option><option value="6h">every 6h</option><option value="24h">daily</option>
                  </Select>
                )}
                {me?.admin && g.status === "setup" && <Button size="sm" variant="secondary" onClick={() => start(g)} title="ring the starting bell now, with seats still open">Start</Button>}
                {me?.admin && g.status === "running" && <Button size="sm" variant="ghost" onClick={() => setStatus(g, "paused")}>Pause</Button>}
                {me?.admin && g.status === "paused" && <Button size="sm" variant="ghost" onClick={() => setStatus(g, "running")}>Resume</Button>}
                {me?.admin && <Button size="sm" variant="ghost" onClick={() => reloadRules(g)} title="replace this game's rule snapshot with the preset as shipped now">Reload rules</Button>}
                {me?.admin && <Button size="sm" variant="ghost" onClick={() => seedSea(g)} title="give the sea its fishing grounds (ocean fertility by region)">Seed fishing grounds</Button>}
                {me?.admin && <Button size="sm" variant="secondary" onClick={() => runUpdate(g)}>Run update</Button>}
                <News game={g} onRead={load} />
                {me?.admin && <Button asChild size="sm" variant="ghost" title="the deity's screen: see everything, change anything"><Link to={`/admin/pogo/${g.id}`}>POGO</Link></Button>}
                {me?.admin && <AddCountry game={g} onAdded={load} />}
                {me?.admin && <DeleteGame game={g} onDeleted={load} />}
                {g.myCountry != null && <RenameCountry game={g} onRenamed={load} />}
                {g.myCountry != null && <Button asChild size="sm"><Link to={`/games/${g.id}`}>Play</Link></Button>}
              </div>
            </div>
            <ul className="mt-2 flex flex-wrap items-center gap-2">
              {g.countries.map(c => (
                <li key={c.id}>
                  {c.id === g.myCountry ? <Badge tone="accent">{c.name} (you)</Badge>
                    : c.taken ? <Badge tone="neutral">{c.name}</Badge>
                    : g.myCountry == null ? <ClaimSeat game={g} seat={c} onClaimed={load} /> : <Badge tone="neutral">{c.name} (open)</Badge>}
                </li>
              ))}
              {g.status === "setup" && (
                <li className="text-xs text-muted-foreground">
                  {g.countries.filter(c => !c.taken).length === 0
                    ? "every seat taken"
                    : `waiting for ${g.countries.filter(c => !c.taken).length} more · the game starts when the last seat is taken`}
                </li>
              )}
            </ul>
          </div>
        ))}
      </section>
      {me?.admin && <CreateGame onCreated={load} />}
    </main>
  );
}

/** One preset's world as shipped, used for the placeholders on the create form. */
interface PresetWorld {
  preset: string; name: string; width: number; height: number;
  wrapX: boolean; wrapY: boolean; water: number;
  islandSize: number; spike: number; minCapitalDistance: number;
  landMix: Record<string, number>; maxCountries: number;
}

const LAND_TERRAINS = ["wilderness", "plains", "forest", "mountain", "swamp"] as const;
type LandTerrain = (typeof LAND_TERRAINS)[number];

const HINTS = {
  preset: "The rule set the world is built from. It decides everything not on this form — the economy, which units exist, how long an update is. The fields below only override its map.",
  countries: "How many seats the game has, up to 64. They are created as emp1, emp2 and so on, and each player names their own when they claim one. Each country gets its own island grown around its capital, so more seats means more land even at the same water percentage. The game does not start until every seat is taken \u2014 or until you start it yourself.",
  seed: "The one number every random draw in this game comes from: the map, and every update's population, plague and detection rolls. The same seed with the same settings and the same moves replays the identical game. Leave it blank for a world nobody has seen.",
  size: "The map in sectors, width × height. Up to 2048 a side and 2,097,152 sectors in all; the largest worlds take about 82 seconds an update.",
  water: "How much of the map is sea. Everything left over is land, split between the countries' own islands and whatever extra islands are needed to fill the quota.",
  islandSize: "Average sectors in one landmass. Small values scatter the land into an archipelago; large values gather it into a few continents that countries may end up sharing.",
  spike: "How ragged the coastlines come out, 0 to 100. High values grow land off the newest edge, making fingers, peninsulas and inlets. Low values grow it evenly, making round blobs.",
  capitalDistance: "The fewest sectors allowed between any two capitals. Raise it to keep players apart early; raise it too far for the map and there is nowhere left to put everyone.",
  wrapX: "The east edge joins the west, so sailing west far enough brings you back around. Turn it off for a map with hard edges you can back into.",
  wrapY: "The north edge joins the south. With both wraps on the world is a torus, which is what the original Empire did. Needs an even height.",
  landMix: "What the land is made of. These are weights, not percentages — they are scaled to fit, so only the ratios between them matter. Mountains carry the minerals and gold, plains carry the fertility, swamp carries the oil, and forest sits between.",
} as const;

/**
 * Deleting a game takes its map, its countries and its whole history with it, and there is no undo.
 * The name has to be typed: a dialog you can dismiss with a reflexive click on "OK" is not a
 * confirmation, and the other admin actions here are all recoverable in a way this one is not.
 */
interface Seated { countryId: number; name: string; capital: { x: number; y: number }; controller: string; token: string | null }

/**
 * Seat a new player in a running game (issue #113). A human seat is left open for someone to join;
 * an agent seat is bound to a fresh bot account and mints a bearer token, which is shown here once
 * and never again — only its hash is kept.
 */
/**
 * Claiming a seat names it (issue #119). Seats are generated as emp1 … empN and nobody wants to play
 * as emp7, so the name is asked for here rather than offered later — the server refuses a claim
 * without one, and taking the last seat rings the starting bell (issue #123).
 */
/**
 * Renaming your own country after the fact (issue #119). Naming happens when a seat is claimed;
 * this is the "you can change it later" half, which the guide promises.
 */
/**
 * What has happened since you last looked (issue #121). Country names are resolved when the feed is
 * read, not when an item is written, so a rename carries backwards through the whole history — the
 * notice announcing it included.
 */
function News({ game, onRead }: { game: GameSummary; onRead: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<NewsItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const show = async (next: boolean) => {
    setOpen(next);
    if (!next) return;
    setItems(null); setError(null);
    try {
      setItems(await api.get<NewsItem[]>(`/games/${game.id}/news`));
      await api.post(`/games/${game.id}/news/seen`);
      await onRead();
    } catch (e) { setError((e as Error).message); }
  };

  return (
    <Dialog open={open} onOpenChange={show}>
      <DialogTrigger asChild>
        <Button size="sm" variant={game.unseenNews > 0 ? "secondary" : "ghost"} title="what has happened in this game">
          News{game.unseenNews > 0 ? ` · ${game.unseenNews}` : ""}
        </Button>
      </DialogTrigger>
      <DialogContent size="lg">
        <DialogHeader>
          <DialogTitle>{game.name}</DialogTitle>
          <DialogDescription>Newest first. Anything you have not seen before is marked.</DialogDescription>
        </DialogHeader>
        <div className="max-h-96 space-y-1 overflow-auto py-2 text-sm">
          {error && <p className="text-destructive">{error}</p>}
          {items === null && !error && <p className="text-muted-foreground">Loading…</p>}
          {items?.length === 0 && <p className="text-muted-foreground">Nothing has happened yet.</p>}
          {items?.map(i => (
            <div key={i.id} className="flex items-baseline gap-2 border-t border-border py-1">
              {i.unseen && <Badge tone="accent">new</Badge>}
              <span className="text-xs text-muted-foreground">update {i.updateNumber}</span>
              <span>{i.text}</span>
            </div>
          ))}
        </div>
        <DialogFooter><Button size="sm" onClick={() => void show(false)}>Close</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function RenameCountry({ game, onRenamed }: { game: GameSummary; onRenamed: () => Promise<void> }) {
  const current = game.countries.find(c => c.id === game.myCountry);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!current) return null;

  const close = (next: boolean) => { setOpen(next); if (!next) { setName(""); setError(null); } };

  const rename = async () => {
    const next = name.trim();
    if (!next || next === current.name) return;
    setBusy(true); setError(null);
    try {
      await api.post(`/games/${game.id}/rename`, { name: next });
      close(false);
      await onRenamed();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };

  return (
    <Dialog open={open} onOpenChange={n => { close(n); if (n) setName(current.name); }}>
      <DialogTrigger asChild>
        <Button size="sm" variant="ghost" title="rename your country">Rename</Button>
      </DialogTrigger>
      <DialogContent size="sm">
        <DialogHeader>
          <DialogTitle>Rename {current.name}</DialogTitle>
          <DialogDescription>
            Everyone who can see your sectors sees this name, and it changes for them at once —
            old map memory included. Names are unique within a game.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2 py-2">
          <label htmlFor={`rn-${game.id}`} className="text-sm">New name</label>
          <Input id={`rn-${game.id}`} value={name} onChange={e => setName(e.target.value)} autoComplete="off" maxLength={40}
                 onKeyDown={e => { if (e.key === "Enter" && name.trim() && !busy) void rename(); }} />
          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>
        <DialogFooter>
          <Button variant="soft" size="sm" onClick={() => close(false)}>Cancel</Button>
          <Button size="sm" disabled={!name.trim() || name.trim() === current.name || busy} onClick={() => void rename()}>
            {busy ? "Renaming…" : "Rename"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ClaimSeat({ game, seat, onClaimed }: { game: GameSummary; seat: CountrySeat; onClaimed: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const last = game.countries.filter(c => !c.taken).length === 1;

  const close = (next: boolean) => { setOpen(next); if (!next) { setName(""); setError(null); } };

  const claim = async () => {
    if (!name.trim()) return;
    setBusy(true); setError(null);
    try {
      await api.post(`/games/${game.id}/join`, { countryId: seat.id, name: name.trim() });
      close(false);
      await onClaimed();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogTrigger asChild>
        <Button size="sm" variant="secondary">Take {seat.name}</Button>
      </DialogTrigger>
      <DialogContent size="sm">
        <DialogHeader>
          <DialogTitle>Name your country</DialogTitle>
          <DialogDescription>
            {seat.name} is a seat number, not a name. Choose what your country is called — everyone
            else will see it on the map. You can change it later.
            {last && " This is the last open seat, so taking it starts the game."}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2 py-2">
          <label htmlFor={`claim-${game.id}-${seat.id}`} className="text-sm">Country name</label>
          <Input id={`claim-${game.id}-${seat.id}`} value={name} onChange={e => setName(e.target.value)} autoComplete="off" maxLength={40}
                 onKeyDown={e => { if (e.key === "Enter" && name.trim() && !busy) void claim(); }} />
          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>
        <DialogFooter>
          <Button variant="soft" size="sm" onClick={() => close(false)}>Cancel</Button>
          <Button size="sm" disabled={!name.trim() || busy} onClick={() => void claim()}>
            {busy ? "Claiming…" : last ? "Claim and start" : "Claim"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function AddCountry({ game, onAdded }: { game: GameSummary; onAdded: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [controller, setController] = useState<"human" | "agent">("human");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [seated, setSeated] = useState<Seated | null>(null);
  const [copied, setCopied] = useState(false);

  const close = (next: boolean) => {
    setOpen(next);
    if (!next) { setName(""); setController("human"); setError(null); setSeated(null); setCopied(false); }
  };

  const add = async () => {
    if (!name.trim()) return;
    setBusy(true); setError(null);
    try {
      const r = await api.post<Seated>(`/admin/games/${game.id}/countries`, { name: name.trim(), controller });
      await onAdded();
      // a bot's token is only ever shown here, so the dialog stays open until it is dismissed
      if (r.token) setSeated(r);
      else close(false);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };

  const copy = async () => {
    if (!seated?.token) return;
    try { await navigator.clipboard.writeText(seated.token); setCopied(true); } catch { setCopied(false); }
  };

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogTrigger asChild>
        <Button size="sm" variant="ghost" title="seat a new player in this game">Add country</Button>
      </DialogTrigger>
      <DialogContent size="sm">
        {seated?.token ? (
          <>
            <DialogHeader>
              <DialogTitle>{seated.name} is seated</DialogTitle>
              <DialogDescription>
                Capital at {seated.capital.x},{seated.capital.y}. Give the bot this token as
                {" "}<code>Authorization: Bearer …</code>. It is shown once — only its hash is stored,
                so if it is lost the seat needs a new one.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-2 py-2">
              <code className="block break-all rounded-[var(--radius)] border border-border bg-muted p-2 text-xs">{seated.token}</code>
              <Button size="sm" variant="soft" onClick={() => void copy()}>{copied ? "Copied" : "Copy token"}</Button>
            </div>
            <DialogFooter><Button size="sm" onClick={() => close(false)}>Done</Button></DialogFooter>
          </>
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>Add a country to “{game.name}”</DialogTitle>
              <DialogDescription>
                A capital and a sanctuary are placed on unowned land, at the preset's minimum distance
                from every other capital. If there is no such sector the world is left alone and this
                says why — whether the land is taken or the capitals are simply spaced too far apart.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-3 py-2">
              <div className="space-y-1">
                <label htmlFor={`ac-name-${game.id}`} className="text-sm">Country name</label>
                <Input id={`ac-name-${game.id}`} value={name} onChange={e => setName(e.target.value)} autoComplete="off"
                       onKeyDown={e => { if (e.key === "Enter" && name.trim() && !busy) void add(); }} />
              </div>
              <RadioGroup>
                <label className="flex items-center gap-2 text-sm">
                  <Radio name={`ac-ctl-${game.id}`} checked={controller === "human"} onChange={() => setController("human")} />
                  <span>Person — the seat stays open to join</span>
                </label>
                <label className="flex items-center gap-2 text-sm">
                  <Radio name={`ac-ctl-${game.id}`} checked={controller === "agent"} onChange={() => setController("agent")} />
                  <span>Agent — bound to a bot account, with a token</span>
                </label>
              </RadioGroup>
              {error && <p className="text-sm text-destructive">{error}</p>}
            </div>
            <DialogFooter>
              <Button variant="soft" size="sm" onClick={() => close(false)}>Cancel</Button>
              <Button size="sm" disabled={!name.trim() || busy} onClick={() => void add()}>{busy ? "Seating…" : "Add country"}</Button>
            </DialogFooter>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

function DeleteGame({ game, onDeleted }: { game: GameSummary; onDeleted: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const matches = typed.trim() === game.name;

  const close = (next: boolean) => { setOpen(next); if (!next) { setTyped(""); setError(null); } };

  const remove = async () => {
    if (!matches) return;
    setBusy(true); setError(null);
    try {
      await api.post(`/admin/games/${game.id}`, undefined, "DELETE");
      close(false);
      await onDeleted();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogTrigger asChild>
        <Button size="sm" variant="ghost" title="delete this game and everything in it">Delete</Button>
      </DialogTrigger>
      <DialogContent size="sm">
        <DialogHeader>
          <DialogTitle>Delete “{game.name}”?</DialogTitle>
          <DialogDescription>
            This removes the {game.width}×{game.height} map, every country and ship, and all{" "}
            {game.updateNumber} updates of history. It cannot be undone.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2 py-2">
          <label htmlFor={`del-${game.id}`} className="text-sm">Type <span className="font-medium text-foreground">{game.name}</span> to confirm</label>
          <Input id={`del-${game.id}`} value={typed} onChange={e => setTyped(e.target.value)} autoComplete="off"
                 onKeyDown={e => { if (e.key === "Enter" && matches && !busy) void remove(); }} />
          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>
        <DialogFooter>
          <Button variant="soft" size="sm" onClick={() => close(false)}>Cancel</Button>
          <Button variant="danger" size="sm" disabled={!matches || busy} onClick={() => void remove()}>
            {busy ? "Deleting…" : "Delete for good"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CreateGame({ onCreated }: { onCreated: () => Promise<void> }) {
  const [name, setName] = useState("New world");
  const [preset, setPreset] = useState("teaching");
  const [countries, setCountries] = useState("2");
  const [seed, setSeed] = useState("");
  const [width, setWidth] = useState("");
  const [height, setHeight] = useState("");
  const [water, setWater] = useState("");
  const [islandSize, setIslandSize] = useState("");
  const [spike, setSpike] = useState("");
  const [capitalDistance, setCapitalDistance] = useState("");
  const [wrapX, setWrapX] = useState<boolean | null>(null);
  const [wrapY, setWrapY] = useState<boolean | null>(null);
  const [mix, setMix] = useState<Record<LandTerrain, string>>({ wilderness: "", plains: "", forest: "", mountain: "", swamp: "" });
  const [presets, setPresets] = useState<PresetWorld[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { api.get<PresetWorld[]>("/admin/presets").then(setPresets).catch(() => setPresets([])); }, []);
  const base = presets.find(p => p.preset === preset);

  const num = (s: string) => (s.trim() ? Number(s) : null);
  const mixPayload = () => {
    const touched = LAND_TERRAINS.filter(t => mix[t].trim() !== "");
    if (touched.length === 0) return null;
    // an untouched terrain keeps the preset's weight, so editing one field does not silently zero the rest
    const out: Record<string, number> = {};
    for (const t of LAND_TERRAINS) out[t] = mix[t].trim() ? Number(mix[t]) : (base?.landMix[t] ?? 0) * 100;
    return out;
  };

  const create = async () => {
    setBusy(true); setError(null);
    try {
      await api.post("/admin/games", {
        name, preset,
        countries: countries.trim() ? Number(countries) : null,
        seed: num(seed), width: num(width), height: num(height), water: num(water),
        islandSize: num(islandSize), spike: num(spike), minCapitalDistance: num(capitalDistance),
        wrapX, wrapY, landMix: mixPayload(),
      });
      await onCreated();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };

  const ph = (v: number | undefined) => (v === undefined ? "preset" : String(v));

  return (
    <section className="space-y-4 rounded-lg border border-border bg-card p-4 text-sm">
      <h2 className="font-medium">Create a world <span className="text-muted-foreground">(deity)</span></h2>

      <div className="grid gap-3 sm:grid-cols-2">
        <label className="space-y-1"><span>Name</span><Input value={name} onChange={e => setName(e.target.value)} /></label>
        <div className="space-y-1">
          <FieldLabel label="Preset" hint={HINTS.preset} htmlFor="cw-preset" />
          <Select id="cw-preset" value={preset} onChange={e => setPreset(e.target.value)}>
            <option value="teaching">teaching (16×16, economy only)</option>
            <option value="sandbox">sandbox</option>
            <option value="blitz">blitz</option>
            <option value="classic">classic (128×64)</option>
          </Select>
        </div>
        <div className="space-y-1">
          <FieldLabel label="Countries" hint={HINTS.countries} htmlFor="cw-countries" />
          <Input id="cw-countries" value={countries} onChange={e => setCountries(e.target.value)} placeholder={base ? String(base.maxCountries) : "2"} inputMode="numeric" />
          <p className="text-xs text-muted-foreground">
            seats named emp1{countries.trim() && Number(countries) > 1 ? `\u2013emp${Number(countries)}` : ""}, named by whoever takes them
          </p>
        </div>
        <div className="space-y-1">
          <FieldLabel label="Seed" hint={HINTS.seed} htmlFor="cw-seed" />
          <div className="flex gap-2">
            <Input id="cw-seed" value={seed} onChange={e => setSeed(e.target.value)} placeholder="random" inputMode="numeric" />
            <Button type="button" variant="soft" size="sm" onClick={() => setSeed(String(Math.floor(Math.random() * 1e9)))}>Roll</Button>
          </div>
        </div>
      </div>

      <fieldset className="space-y-3 rounded-[var(--radius)] border border-border p-3">
        <legend className="px-1 text-xs uppercase tracking-wider text-muted-foreground">Map</legend>
        <div className="grid gap-3 sm:grid-cols-3">
          <div className="space-y-1">
            <FieldLabel label="Width" hint={HINTS.size} htmlFor="cw-w" />
            <Input id="cw-w" value={width} onChange={e => setWidth(e.target.value)} placeholder={ph(base?.width)} inputMode="numeric" />
          </div>
          <div className="space-y-1">
            <FieldLabel label="Height" hint={HINTS.size} htmlFor="cw-h" />
            <Input id="cw-h" value={height} onChange={e => setHeight(e.target.value)} placeholder={ph(base?.height)} inputMode="numeric" />
          </div>
          <div className="space-y-1">
            <FieldLabel label="Water %" hint={HINTS.water} htmlFor="cw-water" />
            <Input id="cw-water" value={water} onChange={e => setWater(e.target.value)} placeholder={ph(base?.water)} inputMode="numeric" />
          </div>
          <div className="space-y-1">
            <FieldLabel label="Island size" hint={HINTS.islandSize} htmlFor="cw-island" />
            <Input id="cw-island" value={islandSize} onChange={e => setIslandSize(e.target.value)} placeholder={ph(base?.islandSize)} inputMode="numeric" />
          </div>
          <div className="space-y-1">
            <FieldLabel label="Spike" hint={HINTS.spike} htmlFor="cw-spike" />
            <Input id="cw-spike" value={spike} onChange={e => setSpike(e.target.value)} placeholder={ph(base?.spike)} inputMode="numeric" />
          </div>
          <div className="space-y-1">
            <FieldLabel label="Capitals apart" hint={HINTS.capitalDistance} htmlFor="cw-cap" />
            <Input id="cw-cap" value={capitalDistance} onChange={e => setCapitalDistance(e.target.value)} placeholder={ph(base?.minCapitalDistance)} inputMode="numeric" />
          </div>
        </div>
        <div className="flex flex-wrap gap-6">
          <span className="inline-flex items-center gap-2">
            <Checkbox id="cw-wrapx" checked={wrapX ?? base?.wrapX ?? true} onChange={e => setWrapX(e.target.checked)} />
            <FieldLabel label="Wrap east–west" hint={HINTS.wrapX} htmlFor="cw-wrapx" />
          </span>
          <span className="inline-flex items-center gap-2">
            <Checkbox id="cw-wrapy" checked={wrapY ?? base?.wrapY ?? true} onChange={e => setWrapY(e.target.checked)} />
            <FieldLabel label="Wrap north–south" hint={HINTS.wrapY} htmlFor="cw-wrapy" />
          </span>
        </div>
      </fieldset>

      <fieldset className="space-y-3 rounded-[var(--radius)] border border-border p-3">
        <legend className="px-1 text-xs uppercase tracking-wider text-muted-foreground">
          <FieldLabel label="Land mix" hint={HINTS.landMix} />
        </legend>
        <div className="grid gap-3 sm:grid-cols-5">
          {LAND_TERRAINS.map(t => (
            <div key={t} className="space-y-1">
              <label className="capitalize" htmlFor={`cw-mix-${t}`}>{t}</label>
              <Input id={`cw-mix-${t}`} value={mix[t]} onChange={e => setMix({ ...mix, [t]: e.target.value })}
                     placeholder={base ? String(Math.round((base.landMix[t] ?? 0) * 100)) : "preset"} inputMode="numeric" />
            </div>
          ))}
        </div>
      </fieldset>

      {error && <p className="text-destructive">{error}</p>}
      <Button disabled={busy} onClick={() => void create()}>{busy ? "Generating…" : "Create"}</Button>
    </section>
  );
}

function intervalLabel(seconds: number): string {
  if (!seconds) return "0";
  if (seconds % 3600 === 0) return `${seconds / 3600}h`;
  if (seconds % 60 === 0) return `${seconds / 60}m`;
  return `${seconds}s`;
}
