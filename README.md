# Empire

A browser-based re-implementation of the 1980s multiplayer strategy game
*Empire* (Peter Langston; Wolfpack/Chainsaw lineage). Sector economy, deep
supply chains, timed world updates, fog of war, and diplomacy between real
people — with a web UI that finally shows the goods moving.

Live at <https://hastingtx.org/empire/> (holding page until M1).

## Read first

- [`docs/build-prompt.md`](docs/build-prompt.md) — the spec.
- [`config/schema.yaml`](config/schema.yaml) — every knob, commented, with
  `KNOWN` / `GUESS` / `NEW` provenance on each mechanic.
- [`docs/update-sequence.md`](docs/update-sequence.md) — the twelve-step
  deterministic update, plan-then-apply.
- [`docs/module-layout.md`](docs/module-layout.md) — Maven modules and the
  dependency rule.

## Status

**M1 in progress** (issue #5): PostgreSQL persistence (Flyway), passwordless
magic-link accounts, REST API, the React map UI (canvas hex map with layers,
sector inspector, console with history, nation dashboard), admin world creation
and a manual update trigger. Deployed as `empire.service` behind nginx at
`/empire/`. M0 (issue #3): engine, config, simulation harness, 23 tests.

```bash
mvn -q test                                   # engine, config, sim, server (server test needs EMPIRE_DB_PASSWORD)
make sim PRESET=teaching UPDATES=60 COUNTRIES=3 SEED=9   # headless run -> sim-out/
./deploy.sh                                   # build web + server, restart the service, check the public URL
```

Local dev: `cd empire-web && npm run dev` proxies `/empire/api` to a server started
from `empire-server/.env` (set `EMPIRE_MAIL_MODE=log` to get magic links in the log).

All numeric rates in `config/schema.yaml` are placeholders; the harness exists
to calibrate them.

## Stack

Java 21 / Spring Boot 3 / PostgreSQL / React + TypeScript + Vite + Tailwind.
