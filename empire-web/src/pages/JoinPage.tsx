import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { ThemeToggle } from "@/components/ui/theme-toggle";

interface OpenSeat { countryId: number; name: string; state: "open" | "taken" | "reserved" }
interface OpenGame {
  id: number; name: string; preset: string; status: string; width: number; height: number;
  updateNumber: number; openSeats: number; seats: OpenSeat[];
}

/**
 * The front door (issue #126). A newcomer takes a seat and names their country here, before having
 * an account at all — the magic link that follows is what proves the email and turns the hold into
 * a seat. One step at the moment of interest, instead of an errand before it.
 */
export function JoinPage() {
  const [games, setGames] = useState<OpenGame[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<{ game: string; country: string; email: string } | null>(null);

  const load = useCallback(() => {
    api.get<OpenGame[]>("/public/games").then(setGames).catch(e => { setError((e as Error).message); setGames([]); });
  }, []);
  useEffect(() => { load(); }, [load]);

  if (sent) return (
    <main className="mx-auto max-w-md space-y-4 p-6">
      <h1 className="text-2xl font-semibold tracking-widest">EMPIRE</h1>
      <div className="space-y-3 rounded-lg border border-border bg-card p-4 text-sm">
        <h2 className="font-medium">{sent.country} is held for you</h2>
        <p className="text-muted-foreground">
          A link is on its way to <span className="text-foreground">{sent.email}</span>. Clicking it signs
          you in and takes the seat — the game will not start without you until then.
        </p>
        <p className="text-muted-foreground">
          The seat is held for half an hour. If the link goes unclicked it goes back on offer, and you can
          simply claim it again.
        </p>
        <Button asChild variant="soft" size="sm"><Link to="/help">Read the guide while you wait</Link></Button>
      </div>
    </main>
  );

  return (
    <main className="mx-auto max-w-3xl space-y-6 p-6">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-widest">EMPIRE</h1>
          <p className="text-sm text-muted-foreground">take a country</p>
        </div>
        <div className="flex items-center gap-2">
          <ThemeToggle />
          <Button asChild variant="soft" size="sm"><Link to="/help">Guide</Link></Button>
          <Button asChild variant="soft" size="sm"><Link to="/login">Sign in</Link></Button>
        </div>
      </header>

      {error && <p className="text-sm text-destructive">{error}</p>}
      {games === null && <p className="text-sm text-muted-foreground">Loading…</p>}
      {games !== null && games.length === 0 && (
        <p className="text-sm text-muted-foreground">
          No game has a seat going just now. If you already have a country, <Link className="underline" to="/login">sign in</Link>.
        </p>
      )}

      {games?.map(g => <Game key={g.id} game={g} onClaimed={setSent} onError={setError} />)}
    </main>
  );
}

function Game({ game, onClaimed, onError }: {
  game: OpenGame;
  onClaimed: (s: { game: string; country: string; email: string }) => void;
  onError: (m: string) => void;
}) {
  const [seat, setSeat] = useState<number | null>(null);
  const [country, setCountry] = useState("");
  const [email, setEmail] = useState("");
  const [you, setYou] = useState("");
  const [busy, setBusy] = useState(false);

  const claim = async () => {
    if (seat == null || !country.trim() || !email.trim()) return;
    setBusy(true);
    try {
      await api.post(`/public/games/${game.id}/claim`, { countryId: seat, name: country.trim(), email: email.trim(), yourName: you.trim() });
      onClaimed({ game: game.name, country: country.trim(), email: email.trim() });
    } catch (e) { onError((e as Error).message); } finally { setBusy(false); }
  };

  return (
    <section className="space-y-3 rounded-lg border border-border bg-card p-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{game.name}</span>
        <Badge tone="muted">{game.preset}</Badge>
        <Badge tone="neutral">{game.width}×{game.height}</Badge>
        {game.status === "setup"
          ? <Badge tone="signal">not started — waiting for {game.openSeats} more</Badge>
          : <Badge tone="neutral">running · update {game.updateNumber}</Badge>}
      </div>

      <ul className="flex flex-wrap gap-2">
        {game.seats.map(s => (
          <li key={s.countryId}>
            {s.state === "open"
              ? <Button size="sm" variant={seat === s.countryId ? "primary" : "secondary"} onClick={() => setSeat(s.countryId)}>{s.name}</Button>
              : <Badge tone="neutral">{s.name} {s.state === "taken" ? "(taken)" : "(being claimed)"}</Badge>}
          </li>
        ))}
      </ul>

      {seat != null && (
        <div className="space-y-3 border-t border-border pt-3">
          <p className="text-xs text-muted-foreground">
            {game.seats.find(s => s.countryId === seat)?.name} is a seat number, not a name. Choose what your
            country is called — everyone else sees it on the map, and you can change it later.
          </p>
          <div className="grid gap-2 sm:grid-cols-3">
            <label className="space-y-1"><span className="text-xs">Country name</span>
              <Input value={country} onChange={e => setCountry(e.target.value)} maxLength={40} autoComplete="off" /></label>
            <label className="space-y-1"><span className="text-xs">Your email</span>
              <Input value={email} onChange={e => setEmail(e.target.value)} type="email" autoComplete="email" placeholder="where the link goes" /></label>
            <label className="space-y-1"><span className="text-xs">Your name (optional)</span>
              <Input value={you} onChange={e => setYou(e.target.value)} autoComplete="name" /></label>
          </div>
          <p className="text-xs text-muted-foreground">
            No password. A link arrives by email; clicking it signs you in and takes the seat. Until then the
            seat is held for you for half an hour, and the game will not start without you.
          </p>
          <Button size="sm" disabled={!country.trim() || !email.trim() || busy} onClick={() => void claim()}>
            {busy ? "Holding the seat…" : "Take this country"}
          </Button>
        </div>
      )}
    </section>
  );
}
