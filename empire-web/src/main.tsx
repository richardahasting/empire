import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import "./index.css";
import { AuthProvider, useAuth } from "./api/auth";
import { LoginPage } from "./pages/LoginPage";
import { VerifyPage } from "./pages/VerifyPage";
import { GamesPage } from "./pages/GamesPage";
import { HelpPage } from "./pages/HelpPage";
import { JoinPage } from "./pages/JoinPage";
import { PogoPage } from "./pages/PogoPage";
import { GamePage } from "./pages/GamePage";
import DesignSystemPage from "./pages/DesignSystemPage";
import { TooltipProvider } from "./components/ui/tooltip";

function Guard({ children }: { children: React.ReactElement }) {
  const { me, loading } = useAuth();
  if (loading) return <main className="p-6 text-sm">Loading…</main>;
  return me ? children : <Navigate to="/login" replace />;
}

function NotFound() { return <main className="p-6 text-sm">Nothing here. <a className="underline" href="/empire/games">Games</a></main>; }

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <TooltipProvider delayDuration={200}>
    <AuthProvider>
      <BrowserRouter basename="/empire">
        <Routes>
          <Route path="/" element={<Navigate to="/games" replace />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/join" element={<JoinPage />} />
          <Route path="/verify" element={<VerifyPage />} />
          <Route path="/help" element={<HelpPage />} />
          <Route path="/help/:slug" element={<HelpPage />} />
          <Route path="/admin/pogo/:id" element={<Guard><PogoPage /></Guard>} />
          <Route path="/games" element={<Guard><GamesPage /></Guard>} />
          <Route path="/games/:id" element={<Guard><GamePage /></Guard>} />
          <Route path="/admin/design-system" element={<DesignSystemPage />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
    </TooltipProvider>
  </StrictMode>
);
