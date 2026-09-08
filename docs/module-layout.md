# Module and package layout

Maven multi-module build, Java 21. The engine is the centre of gravity and
has **no** dependencies beyond the JDK and (test-scope only) JUnit + jqwik.
Everything else depends inward on it; nothing depends outward on the server.

```
empire/
├── pom.xml                         # parent: versions, modules, java.release=21
├── config/
│   ├── schema.yaml                 # the fully-populated commented schema
│   ├── units.yaml                  # M5 unit capability table
│   └── presets/{classic,blitz,teaching,sandbox}.yaml
├── docs/
│   ├── build-prompt.md             # the spec
│   ├── update-sequence.md          # order of operations (kept accurate)
│   └── module-layout.md            # this file
│
├── empire-engine/                  # M0. Pure Java. Zero I/O, zero framework.
│   └── src/main/java/org/hastingtx/empire/engine/
│       ├── model/                  # immutable records: World, Sector, Country, Stocks,
│       │                           #   Contact, HeldParcel, RailLine, Levels, Handicap
│       ├── geo/                    # Hex (offset<->axial<->player-facing), Adjacency,
│       │                           #   Rotation (for the symmetry tests), Pathfinding
│       ├── gen/                    # WorldGenerator (fairland), ResourceAssigner
│       ├── update/                 # Update.run(snapshot, cfg, seed) -> UpdateResult
│       │   ├── Ledger.java         #   delta ledger with source/sink attribution
│       │   ├── steps/              #   one class per numbered step in update-sequence.md
│       │   │   ├── Accrual, Population, BuildUp, Production, FlowPlanner,
│       │   │   ├── Contention, Money, Levels, Detection, News, Apply
│       │   └── Rng.java            #   per-step SplittableRandom streams
│       ├── command/                # Command sealed interface + one record per verb
│       │                           #   (Designate, Move, Distribute, Threshold, RailShip,
│       │                           #   Telegram, Announce, BuildRoad, ...), CommandValidator,
│       │                           #   CommandExecutor (between-update mutations, BTU debit)
│       ├── view/                   # CountryView: the fog-of-war projection of World for one
│       │                           #   country. THE ONLY thing agents and the UI ever see.
│       │                           #   Has no controller flag on any country. Enforced by test.
│       └── config/                 # GameConfig record tree (the schema as types). No parser.
│
├── empire-config/                  # YAML loader + validation (SnakeYAML). Produces GameConfig.
│   └── .../config/{ConfigLoader, PresetResolver (extends:), SchemaValidator, ConfigHash}
│
├── empire-agent-api/               # AgentController interface + TurnContext + AgentTurnLog.
│   └── .../agent/{AgentController, TurnContext, TurnResult, Scratchpad}
│                                   # Depends on engine (for CountryView, Command). Nothing else.
│
├── empire-agents/                  # Implementations.
│   └── .../agents/
│       ├── scripted/               # ScriptedAgent — opening book, M0
│       ├── heuristic/              # HeuristicAgent — M3
│       └── llm/                    # LlmAgent + AnthropicAdapter, OpenAiAdapter, XaiAdapter — M3
│                                   #   prompt framing: inbound telegrams delimited as rival speech
│
├── empire-sim/                     # Headless harness, M0. CLI: run N updates from a preset with a
│   └── .../sim/                    #   roster of agents; CSV per update per country; events log;
│       ├── Sim                     #   state hash. Also hosts the engine's INTEGRATION tests
│       ├── SimRunner               #   (symmetry, rotation, golden, conservation, probes) because
│       ├── Scoring                 #   they need a loaded config and empire-engine cannot depend
│       ├── Tournament              #   on empire-config (M3)
│       └── Bisection               #   handicap calibration (M3)
│
├── empire-server/                  # Spring Boot 4 (4.0.6 is what this box runs; same API surface as 3.x). M1+.
│   └── src/main/java/org/hastingtx/empire/server/
│       ├── persistence/            # Hand-mapped JDBC (JdbcTemplate/JdbcClient), no JPA: engine records are
│       │                           #   immutable and diff-saved. Tables: game, country, sector, sector_stock
│       │                           #   (normalised), held_parcel, move_order, update_log, command_log,
│       │                           #   account, auth_token. Flyway in resources/db/migration.
│       ├── auth/                   # magic link -> session token (both stored as SHA-256), Bearer interceptor,
│       │                           #   Mailer (smtp via Postfix on localhost, or log mode for dev/tests)
│       ├── game/                   # GameService: loaded games in memory behind a lock, write-through
│       ├── console/                # text verbs -> the same Command records -> the same executor; map/census text
│       ├── api/                    # /api/auth, /api/games (view, rules, command, console, last-update), /api/admin
│       └── (ws/, scheduler/)       # M2/M3
│   └── src/main/resources/
│       ├── application.yaml        # server.servlet.context-path=/empire, port 8020, ${EMPIRE_*} env
│       └── static/                 # empire-web/dist copied in by the Maven resources plugin at package time
│
├── empire-web/                     # React + TypeScript + Vite + Tailwind. Not a Maven module;
│   ├── vite.config.ts              #   base: '/empire/'. Built by frontend-maven-plugin or
│   └── src/                        #   a plain npm script that copies dist/ into empire-server.
│       ├── map/                    # Canvas/WebGL hex renderer, layers, flow animation, scrubber
│       ├── inspector/              # sector inspector
│       ├── console/                # command line with history; calls the same /api/command
│       ├── dashboard/              # nation dashboard
│       ├── comms/                  # telegrams, announcements, news
│       └── api/                    # typed client; CountryView types generated from server
│
├── docker-compose.yml              # postgres + server; `make world PRESET=teaching`
└── Makefile                        # build, test, sim, world, deploy
```

## Dependency rule

```
empire-web  ──HTTP/WS──►  empire-server ──►  empire-config ──►  empire-engine
                              │                                     ▲
                              ├──►  empire-agents ──►  empire-agent-api ──┘
                              └──►  empire-sim ───────────────────────────┘
```

- `empire-engine` depends on nothing. A `maven-enforcer` ban on any
  `org.springframework`, `jakarta.persistence`, `java.io`, `java.nio.file`,
  `java.net`, `java.sql`, `java.time.Clock` import in the engine fails the
  build. (`java.time.Instant`/`Duration` as *values* are fine; reading the
  clock is not.)
- `CountryView` is the only type that crosses from engine to agents and to
  the web API. A test serialises it for every country in a fixture world and
  asserts the string `controller` never appears — that is the one careless
  field the spec warns about.
- Persistence entities are separate classes from engine records, mapped
  explicitly. The engine never sees an `@Entity`.

## Package naming

`org.hastingtx.empire.<module>.<area>`. Test packages mirror main. Golden
fixtures under `empire-engine/src/test/resources/golden/<preset>/<seed>/`.

## Milestone map

| Milestone | Modules touched |
|---|---|
| M0 | engine, config, agent-api, agents/scripted, sim |
| M1 | server (persistence, auth, api, console), web (map, inspector) |
| M2 | engine (flow planner, roads, money), web (flow animation) |
| M3 | engine (view/fog, detection, BTU), agents (heuristic, llm), sim (tournament, bisection), server (ws, admin) |
| M4 | engine (levels, efficiency, rail), config (full sector table) |
| M5 | engine (units, combat), config (units.yaml) |

## Deployment (linuxserver)

- Spring Boot fat jar as `empire.service` (systemd, `ops/empire.service`),
  bound to `127.0.0.1:8020`, context path `/empire`. `./deploy.sh` builds and
  restarts. Secrets in `empire-server/.env` (never committed).
- nginx already proxies `hastingtx.org/empire/` → `127.0.0.1:8020` with
  WebSocket upgrade; `www.` 301s to the bare host. While the service is
  down, nginx serves `/var/www/empire/holding.html`.
- PostgreSQL: role `empire_user`, database `empire` (created 2026-09-07 and
  recorded in the shared-server table in `~/CLAUDE.md`).
