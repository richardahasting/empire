import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api, setToken, type Me } from "@/api/client";
import { useAuth } from "@/api/auth";

/** The link target: burns the magic token, stores the session token, goes to the games list. */
export function VerifyPage() {
  const [params] = useSearchParams();
  const nav = useNavigate();
  const { refresh } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const ran = useRef(false);
  useEffect(() => {
    if (ran.current) return; ran.current = true;   // React strict mode double-invokes effects; the token is single-use
    const token = params.get("token");
    if (!token) { setError("no token in the link"); return; }
    api.post<{ token: string; account: Me }>("/auth/verify", { token })
      .then(async r => { setToken(r.token); await refresh(); nav("/games", { replace: true }); })
      .catch(e => setError((e as Error).message));
  }, [params, nav, refresh]);
  return <main className="p-6 text-sm">{error ? <p className="text-destructive">{error} — <a className="underline" href="/empire/login">request a new link</a></p> : <p>Signing you in…</p>}</main>;
}
