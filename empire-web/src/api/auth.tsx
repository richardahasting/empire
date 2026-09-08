import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { api, getToken, setToken, type Me } from "./client";

interface AuthState { me: Me | null; loading: boolean; refresh: () => Promise<void>; logout: () => Promise<void> }
const Ctx = createContext<AuthState>({ me: null, loading: true, refresh: async () => {}, logout: async () => {} });

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const refresh = useCallback(async () => {
    if (!getToken()) { setMe(null); setLoading(false); return; }
    try { setMe(await api.get<Me>("/me")); } catch { setMe(null); } finally { setLoading(false); }
  }, []);
  const logout = useCallback(async () => {
    try { await api.post("/auth/logout"); } catch { /* token may already be dead */ }
    setToken(null); setMe(null);
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  return <Ctx.Provider value={{ me, loading, refresh, logout }}>{children}</Ctx.Provider>;
}
export const useAuth = () => useContext(Ctx);
