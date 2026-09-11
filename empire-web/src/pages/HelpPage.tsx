import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { ThemeToggle } from "@/components/ui/theme-toggle";

/** One guide page, as tools/build-docs.py rendered it. */
interface GuidePage { slug: string; title: string }

const BASE = "/empire/guide";

/**
 * The player guide (issue #118). The Markdown in docs/guide/ is the source of truth and stays
 * readable as plain text; the build renders it through mdview and drops the fragments in
 * public/guide/, which this fetches — a different path from this page's own /help route, so the
 * SPA fallback cannot intercept them. Styling comes from the design tokens rather than from mdview's
 * own page chrome, so the guide follows the app's theme instead of fighting it.
 */
export function HelpPage() {
  const { slug } = useParams();
  const page = slug ?? "index";
  const [pages, setPages] = useState<GuidePage[]>([]);
  const [html, setHtml] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(`${BASE}/index.json`).then(r => r.ok ? r.json() : []).then(setPages).catch(() => setPages([]));
  }, []);

  const load = useCallback(async () => {
    setHtml(null); setError(null);
    try {
      const r = await fetch(`${BASE}/${page}.html`);
      if (!r.ok) throw new Error(`there is no guide page called “${page}”`);
      setHtml(await r.text());
    } catch (e) { setError((e as Error).message); }
  }, [page]);

  useEffect(() => { void load(); }, [load]);

  // links between guide pages are written as bare .html in the Markdown; keep them inside the app
  const onClick = (e: React.MouseEvent<HTMLDivElement>) => {
    const a = (e.target as HTMLElement).closest("a");
    const href = a?.getAttribute("href");
    if (!a || !href || /^[a-z]+:|^\/|^#/.test(href)) return;
    e.preventDefault();
    const to = href.replace(/\.html$/, "").replace(/^\.\//, "");
    window.history.pushState({}, "", `/empire/help/${to === "index" ? "" : to}`);
    window.dispatchEvent(new PopStateEvent("popstate"));
  };

  return (
    <main className="mx-auto max-w-5xl space-y-6 p-6">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-widest">EMPIRE</h1>
          <p className="text-sm text-muted-foreground">the guide</p>
        </div>
        <div className="flex items-center gap-2">
          <ThemeToggle />
          <Button asChild variant="soft" size="sm"><Link to="/games">Games</Link></Button>
        </div>
      </header>

      <div className="grid gap-6 md:grid-cols-[14rem_1fr]">
        <nav className="space-y-1 text-sm">
          {pages.map(p => (
            <Link key={p.slug}
                  to={p.slug === "index" ? "/help" : `/help/${p.slug}`}
                  className={`block rounded-[var(--radius)] px-2 py-1 transition-colors hover:bg-muted ${
                    p.slug === page ? "bg-muted font-medium text-foreground" : "text-muted-foreground"}`}>
              {p.title}
            </Link>
          ))}
          {pages.length === 0 && <p className="text-muted-foreground">The guide was not built into this deployment.</p>}
        </nav>

        <article className="min-w-0">
          {error && <p className="text-sm text-destructive">{error}</p>}
          {!error && html === null && <p className="text-sm text-muted-foreground">Loading…</p>}
          {html !== null && (
            <div className="guide" onClick={onClick} dangerouslySetInnerHTML={{ __html: html }} />
          )}
        </article>
      </div>
    </main>
  );
}
