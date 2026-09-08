# CLAUDE.md — empire

Browser re-implementation of the 1980s multiplayer strategy game Empire. Read
`docs/build-prompt.md` (the spec), `config/schema.yaml` (every rule, with
KNOWN/GUESS/NEW provenance), `docs/update-sequence.md`, `docs/module-layout.md`.

- Engine (`empire-engine`) has no I/O and no framework imports; a test enforces it.
- Every rule lives in `config/schema.yaml`. A numeric literal in engine logic is a bug.
- Agents are ordinary players. `CountryView` never carries controller information.
- The scripted agent is a deterministic test fixture; nobody hand-tunes bot strategy.
- Frontend: `empire-web` (React 19, Vite, Tailwind v4). Built output is bundled into
  `empire-server` at package time (`make build`). Base path `/empire/`.

## Design System (REQUIRED for all UI work)
This project has a documented design system. Before writing any UI:
- View the living reference at `/admin/design-system` (component source: `empire-web/src/components/ui/`).
- Reuse the documented primitives (Button, Badge, Input, Select, Checkbox, Radio, Dialog, DropdownMenu, ThemeToggle). Do not hand-roll equivalents.
- NEVER write raw hex colors or off-system fonts. Use the design tokens — CSS custom properties in `empire-web/src/tokens.css`, e.g. `var(--primary)`, `var(--foreground)`, `var(--border)`.
- If a needed primitive does not exist, add it to `empire-web/src/components/ui/` AND to the reference page — do not inline a one-off.
- The hex map is a `<canvas>`; its palette is defined once in `empire-web/src/map/palette.ts` from the same tokens plus a small set of map-only semantic colors. Do not scatter colors through the renderer.
