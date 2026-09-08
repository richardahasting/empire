import { useState } from "react";
import { api } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

/** Passwordless: name + email, then a one-time link by mail. */
export function LoginPage() {
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setBusy(true); setError(null);
    try { await api.post("/auth/request-link", { email, name }); setSent(true); }
    catch (err) { setError((err as Error).message); }
    finally { setBusy(false); }
  };
  return (
    <main className="mx-auto flex min-h-full max-w-sm flex-col justify-center gap-6 p-6">
      <header><h1 className="text-2xl font-semibold tracking-widest">EMPIRE</h1><p className="text-sm text-muted-foreground">Logistics under uncertainty.</p></header>
      {sent ? (
        <p className="text-sm">Check your mail for a sign-in link. It works once and expires in 30 minutes.</p>
      ) : (
        <form onSubmit={submit} className="space-y-3">
          <label className="block text-sm">Name<Input value={name} onChange={e => setName(e.target.value)} autoComplete="name" placeholder="needed the first time" /></label>
          <label className="block text-sm">Email<Input type="email" required value={email} onChange={e => setEmail(e.target.value)} autoComplete="email" /></label>
          {error && <p className="text-sm text-destructive">{error}</p>}
          <Button type="submit" disabled={busy || !email}>Send me a sign-in link</Button>
        </form>
      )}
    </main>
  );
}
