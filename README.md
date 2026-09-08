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

**M4 done** (issue #27): the four national levels with the original's formulas, rail
networks and depots (build orders, depot-to-depot trains with capacity, range,
holding and stranding, a rail map layer), on top of the rules reconciliation with
the Wolfpack source (#24, `docs/original-rules.md`). **M2 done** (#18): roads,
budget projection, scheduled updates, flow animation. **M1** (issue #5): PostgreSQL persistence (Flyway), passwordless
magic-link accounts, REST API, the React map UI (canvas hex map with layers,
sector inspector, console with history, nation dashboard), admin world creation
and a manual update trigger. Deployed as `empire.service` behind nginx at
`/empire/`. M0 (issue #3): engine, config, simulation harness, 23 tests.

```bash
mvn -q test                                   # engine, config, sim, server (server test needs EMPIRE_DB_PASSWORD)
make sim PRESET=teaching UPDATES=60 COUNTRIES=3 SEED=9   # headless run -> sim-out/
./deploy.sh                                   # build web + server, restart the service, check the public URL
```

**Terminal client** (the original was played over telnet; this is the same idea over the
console API, so every line is the same command the web page sends):

```bash
tools/empire-cli.py                                  # https://hastingtx.org/empire
tools/empire-cli.py --url http://127.0.0.1:8020/empire --game 14
```

Sign in with a magic link (paste the link or its token), pick or join a game, then type
the original's verbs at `empire>` — `map`, `census`, `des`, `thresh`, `dist`, `move`,
`expl`, `road`, `rail`, `railship`, `help` — plus client-side `view`, `projection`,
`games`, `game N`, and for the deity `update` and `schedule 15m`. Python 3, no packages.

Local dev: `cd empire-web && npm run dev` proxies `/empire/api` to a server started
from `empire-server/.env` (set `EMPIRE_MAIL_MODE=log` to get magic links in the log).

All numeric rates in `config/schema.yaml` are placeholders; the harness exists
to calibrate them.

## Stack

Java 21 / Spring Boot 3 / PostgreSQL / React + TypeScript + Vite + Tailwind.
