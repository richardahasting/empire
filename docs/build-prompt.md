# Build Prompt: "Empire" — Modern Re-implementation

> Canonical copy lives in the Google Doc this was pasted from
> (id `1XzaCWX4GDlG1oakWL9TDg0kEp9rZhLKyb_XgnWKouvI`). This file is the
> repo's working copy so agents and reviewers do not need Drive access.
> Transcribed 2026-09-07; if the two disagree, the Doc wins and this file
> should be corrected.

## Mission

Build a modern, browser-based re-implementation of the classic multiplayer text strategy game **Empire** (Peter Langston's original, later the Wolfpack/Chainsaw server lineage) that ran on university networks in the 1980s. Preserve the mechanical soul of the original — sector-based economy, deep supply chains, timed world updates, fog of war, real diplomacy between real humans — while replacing the telnet client with a web UI that visualizes the movement of goods, services, and people across the map.

This is not a Civilization clone. The defining pleasure of Empire was *logistics under uncertainty*: you never had enough mobility, your ore was in the wrong place, and someone else's tanks were coming.

## Non-goals

- No AI opponents. Empire is a game about other people.
- No real-time twitch play. The update tick is the heartbeat.
- No monetization, no accounts beyond what a game session needs.
- Do not attempt binary or protocol compatibility with the original C server.

## Core domain model

**World.** A grid of sectors, hexagonal adjacency (offset coordinates internally, but expose per-country relative coordinates centered on that country's capital, as the original did). Terrain types at minimum: ocean, wilderness, mountain, plains, forest. Each land sector has independent fertility, minerals, gold_content, oil_content, uranium_content values assigned at world generation.

**Country.** A player. Starts with exactly two sanctuary sectors — one designated **capital**, one undesignated — plus a starting cash balance and a starting stock of commodities and civilians. The world is frozen for that country until they "break sanctuary."

**Sector designation.** The player assigns each owned sector a designation that determines what it produces and consumes. Ship at least these, all data-driven (see Configuration):

- Extraction: mine, gold mine, oil field, uranium mine, agribusiness, forest
- Manufacturing: light manufacturing, heavy industry, shell/ammo plant, gun plant, refinery, defense plant
- Infrastructure: capital, warehouse, harbor, airfield, depot, fortress, radar station
- Social: bank, school, university, hospital, library/research lab, enlistment center

**Commodities.** Civilians, military, uncompensated workers, food, iron ore, gold dust, gold bars, oil, petrol, shells, guns, light construction materials, heavy construction materials, uranium/radioactive material. Every sector holds a per-commodity stock with a maximum determined by designation and efficiency.

**Sector attributes.** Owner, designation, efficiency (0–100%, built up over successive updates by spending materials), mobility, civilian/military/worker population, loyalty, and per-commodity stocks and distribution thresholds.

**Distribution.** Each sector may name a distribution center and per-commodity thresholds. At each update, commodities flow along distribution paths toward or away from the center, consuming mobility. This is the mechanic the UI must make legible — it was invisible in the original and it is where most of the strategy lives.

**Reach limits on automatic flow.** Distribution is not unlimited. Removing the scan-order artifact removes the old accidental limiter, so an explicit and symmetric one takes its place. A commodity moving automatically along a distribution path in a single update is bounded by two things:

1. **Mobility drawn from every sector it transits**, not just the endpoints. Each hop debits the transited sector's own mobility pool, scaled by terrain and reduced by that sector's road_level. A route through a well-paved corridor carries far more than the same distance through raw wilderness, and a chain of exhausted sectors chokes the flow regardless of how much surplus sits at the source.
2. **A maximum reach in sectors per update**, derived from national tech level and modified by road quality along the route. Low-tech countries move food a few sectors; a developed one supplies a distant front automatically.

When a shipment exhausts either budget it stops where it is, holds in that sector, and resumes at the next update. Nothing is silently destroyed or teleported. The UI must show partial deliveries as such — a flow arrow that ends short of its destination is exactly the diagnostic a player needs.

**Mobility.** Sectors accrue mobility per update; moving goods and units spends it. Terrain and infrastructure modify cost.

**Transportation infrastructure — a correction to the original.** In the original, a road was a *designation*: an entire sector became a highway and produced nothing else. That is wrong on its face. A road runs through farmland, through a mine, through a city; the fact that trucks pass over a sector says nothing about whether that sector grows wheat. Later versions of the server moved toward per-sector infrastructure levels, and this implementation should commit to it fully.

Every sector carries transportation attributes **independent of its designation**, built up with construction materials and money over successive updates the same way efficiency is:

- road_level (0–100) — **local, sector-by-sector.** Reduces the mobility cost of moving goods and units into, out of, and through that sector, on a diminishing-returns curve from config. Requires no connectivity to be useful: paving one sector helps every route that crosses it. This is the incremental, always-worthwhile investment, and it is how a player grinds out a functioning interior.
- rail_level (0–100) — **long-haul, network.** Rail confers no benefit at all in isolation. It functions only along a **contiguous chain of rail-capable sectors linking two depots**, and it moves bulk commodities between those depots at a cost that scales with shipment size rather than with distance. Building rail requires higher tech, far more heavy construction materials, and a bridge or tunnel to cross water or mountains.

Design constraints on rail, because the entire game is scarcity of movement and a cheap teleporter would dissolve it:

- **Capacity per update**, set in config and scaling with rail_level and depot efficiency. A line is a pipe with a diameter, not an unlimited conduit.
- **Fragile by design.** Severing any single link — capture, bombardment, or letting maintenance lapse — breaks the whole route until repaired. A rail network is a strategic asset and therefore a strategic target, which is the point.
- **Maintenance.** Both road and rail decay a configurable amount per update and cost money to hold at level. Infrastructure a player cannot afford should visibly rot.
- Terrain sets a cost multiplier and a ceiling: mountains and swamp are expensive and may cap the achievable level.

Both attributes feed directly into the update's flow planner as edge weights (road) and as separate high-capacity edges between depots (rail). Neither may be blocked or overwritten by designation — redesignating a sector never destroys its roadbed. Combat, bombardment, and fallout may damage it.

**Rail shipments are addressed to a destination, never to a direction.** A player issues a shipment as *"send N tons of ore from depot A to depot B,"* and the engine resolves the route. Specifically:

- **Connectivity is validated at issue time.** If no contiguous rail path exists between the two depots, the command is rejected immediately with a clear explanation and the name of the sector where the line breaks. It does not silently accept and then strand the cargo.
- **A shipment may be split.** One command may name several destinations with quantities, and the planner apportions the load across them subject to line capacity.
- **Last mile is road, not rail.** Cargo arriving at a destination depot may be pushed out to adjacent sectors using ordinary mobility and roads, either manually or automatically via that depot's distribution thresholds. Rail moves bulk between hubs; roads handle the final spread.
- **Trains have a per-update range.** Maximum rail sectors traversed in one update derives from national tech level, the rail_level of the segments along the route, and depot efficiency. A shipment exceeding that range advances as far as it can, holds at the furthest rail sector reached, and continues at the next update — a train in transit, visible on the map and vulnerable to interdiction there.
- **A line severed mid-transit strands the cargo** at its current position rather than destroying it. The owner must repair the break, reroute, or unload to road.

The same range-and-hold rule applies to manual sector-to-sector movement of goods and units: everything in this world travels at a finite speed and can be caught in the open.

**Cargo in transit is capturable — spoils of war.** Goods held mid-route, whether a partial distribution delivery that ran out of mobility or a train stopped short of its range, physically occupy the sector they stopped in. If an enemy takes that sector, they take what is sitting in it. Nothing evaporates on capture.

- A configurable fraction is destroyed in the fighting rather than seized; the rest transfers to the capturing country's stock in that sector.
- **The attacker cannot see the haul in advance.** Cargo held in a sector is not visible to detection beyond whatever partial signature the config assigns it, so raiding a rail corridor is a gamble that occasionally pays enormously. That uncertainty is the point.
- **Scorched earth.** The owner may order held cargo destroyed rather than surrendered, at a BTU cost, but only as a standing order set *before* the sector falls — no retroactive denial after seeing the outcome. Config flag options.scorched_earth to disable it entirely.
- Depots, roads, and rail levels transfer with the sector as well, at a damage-reduced level. Capturing a rail hub with three loaded trains sitting in it should be one of the great moments available in this game.

This makes supply lines worth raiding rather than merely worth cutting, and it gives the fragility of rail a second dimension: a severed line does not just stop traffic, it parks it somewhere you may not be able to defend.

**Detection and radar.** Radar remains a sector designation — seeing far should cost real estate — with a radar_level built up like efficiency. Detection is **probabilistic, not binary**. For each potential contact, compute a detection probability per update from:

- distance from the station relative to its nominal range
- radar_level, sector efficiency, and national tech level
- **terrain elevation** of the station, which should matter substantially — a mountain installation reaching well past an identical one sited on plains
- the size and signature of the target: a fleet or an army is spotted far sooner and more reliably than a single scout or a lone plane
- weather or terrain masking, if implemented, as a config-driven modifier

Probability decays smoothly toward the edge of range rather than cutting off, so the fringe produces intermittent, uncertain contacts. Surface that honestly in the UI: a firm contact renders differently from a probable one, and stale contacts age visibly rather than lingering as though current. A player should be able to tell the difference between "there is an army there" and "there was something there two updates ago."

Ship, plane, and satellite detection are separate systems with their own ranges and probabilities, so a naval power is not blind for want of land. Radar stations degrade, can be destroyed, and are worth destroying.

**Tech, research, education, happiness.** Four national levels driven by production in the corresponding sector types, gating what units and structures can be built. Keep them, but make each one's contribution curve a config parameter.

**Units.** Ships, planes, and land units with a data-driven capability table gated on tech level. Build them in sectors with sufficient efficiency and materials. Movement, cargo capacity, fuel, and combat resolution. **Ship this last** — the economy must be fun before combat matters.

**Bureaucratic Time Units (BTUs).** Player actions cost BTUs, which accrue from civilians in capital sectors. This is the original's rate limiter and it should stay; it is what makes the game playable asynchronously by people with jobs.

## The update tick

The world advances in discrete **updates** on a schedule set by the game's administrator (the original called them "deities"). Everything that happens — production, consumption, population growth, starvation, distribution, efficiency gains, mobility accrual, budget settlement — happens inside a single update, in a documented, fixed order.

Requirements:

1. The update must be a **pure, deterministic function** of (world state, config, seeded RNG stream) → new world state. No wall-clock reads, no database queries mid-computation.
2. It must be **replayable**. Persist each update's input seed and the resulting diff so any tick can be re-run and audited.
3. It must run **headless** in a simulation harness — the game is unbalanceable without the ability to run a thousand simulated updates in a test and chart the outputs.
4. Wrap it in a transaction. A failed update rolls back cleanly and alerts the administrator.

### Direction independence — a defect of the original that must not be reproduced

The original server processed sectors in scan order, upper-left to lower-right. A supply chain running southeast would move goods many sectors in a single update, because each downstream sector was processed *after* its upstream neighbor had already received its delivery. The same chain running north or west moved one sector per update. Players learned to build their economies diagonally down and to the right. This was an artifact of a single-pass loop over a memory array on hardware that could not afford anything better, and it is not a game mechanic. Eliminate it.

Implement the update as **plan-then-apply against a frozen snapshot**:

1. **Snapshot.** Freeze world state at the start of the update. All planning reads from the snapshot; nothing reads partially-updated state.
2. **Plan.** Compute every intended transfer as a flow on a graph — sources, sinks, routes, and the mobility each route consumes. Distribution and delivery resolve along full paths to their destination in one update, so a chain delivers the same distance regardless of compass direction.
3. **Resolve contention.** Once transfers are simultaneous, iteration order no longer decides who wins a contested resource — but something must. Where demand exceeds supply at a source, or claimed movement exceeds a sector's available mobility, allocate **proportionally to demand**, with an explicit per-commodity priority ordering from config as the tiebreak, and a seeded-RNG tiebreak of last resort. Never fall back to "whichever sector we happened to visit first."
4. **Apply.** Write all deltas atomically.

Add a test that proves it: build the identical supply chain in all six hex directions on an otherwise symmetric map, run one update, and assert that all six produce identical results. Extend that to a rotational-symmetry test on the whole update — rotate a world 60° and the update output must rotate with it.

Also implement **ETUs** (Empire Time Units): an update consists of N ETUs, and per-ETU rates are what the config actually specifies. This is how the original scaled the same rules from a 5-month campaign to a one-day blitz, and it should work the same way here.

## Configuration — everything is a knob

World creation takes a single validated YAML file. Nothing gameplay-relevant may be hardcoded. At minimum:

```yaml
world:
  width: 128
  height: 64
  seed: 20260907
  terrain: { land_fraction: 0.30, island_size: 25, continent_style: fairland }

players:
  max_countries: 40
  starting_cash: 5000
  starting_commodities: { civ: 700, mil: 30, food: 1000, ... }
  countries:                    # optional per-country overrides
    - name: Sonnet
      controller: agent
      agent: { adapter: anthropic, model: claude-sonnet-4-6 }
      handicap: { btu_rate: 0.80 }
    - name: Grok
      controller: agent
      agent: { adapter: xai, model: grok-4 }
    - name: Rick
      controller: human

schedule:
  update_interval: 24h          # or 15m for a blitz
  etus_per_update: 60
  first_update_at: 2026-10-01T03:00:00Z

economy:
  sector_types:                 # full table, one entry per designation
    - id: agribusiness
      cost: 10
      produces: { food: ... }
      consumes: { civ_work: ... }
      max_population: 1000
      build_materials: { lcm: 3 }
  production_rates: { ... }
  consumption_rates: { food_per_civ_per_etu: 0.0005, ... }
  mobility: { accrual_per_etu: 0.5, move_cost_by_terrain: { ... } }

infrastructure:
  road:
    build_materials_per_point: { lcm: 1.2, cash: 8 }
    cost_multiplier_by_terrain: { plains: 1.0, forest: 1.6, mountain: 3.0 }
    max_level_by_terrain: { plains: 100, mountain: 60 }
    mobility_discount_curve: diminishing   # cost multiplier at level 100
    decay_per_update: 0.5
  rail:
    tech_required: 60
    build_materials_per_point: { hcm: 2.0, lcm: 1.0, cash: 30 }
    requires_depot_endpoints: true
    capacity_per_update_at_100: 5000
    cost_per_shipment: by_volume           # not by distance
    decay_per_update: 1.0
    severed_route_behavior: strand_in_place
    max_sectors_per_update: { base: 8, per_tech_point: 0.05 }

distribution:
  max_reach_sectors: { base: 3, per_tech_point: 0.02 }
  mobility_debited_from: transited_sectors
  partial_delivery: hold_in_place

detection:
  model: probabilistic
  radar: { nominal_range_at_100: 12, elevation_bonus_per_meter: ..., decay_curve: sigmoid }
  target_signature: { army: 1.8, fleet: 2.0, single_unit: 0.6 }
  contact_staleness_updates: 3

tech:
  curves: { research_to_tech: ..., education_to_research: ... }

units:
  enabled: true
  table: units.yaml

options:                        # feature flags, as the original had
  fallout: true
  loans: false
  market: true
  hidden: true
```

Provide 3–4 preset configs: **classic** (128×64, daily updates, months long), **blitz** (32×32, 15-minute updates, one evening), **teaching** (tiny world, no units, economy only), and **sandbox**.

## Technology stack

- **Backend:** Java 21, Spring Boot 3. Domain/engine as a dependency-free module (empire-engine) with zero Spring or JPA annotations — pure objects and functions, so it can be unit-tested and simulated in isolation. A separate empire-server module handles persistence, auth, HTTP, and WebSocket.
- **Persistence:** PostgreSQL. World state in normalized tables; update diffs and player commands in an append-only log. Flyway migrations.
- **Transport:** REST for commands and queries; WebSocket (STOMP or raw) for push — update completion, telegrams, announcements, contact/radar events.
- **Frontend:** React + TypeScript + Vite, Tailwind. Map rendered on Canvas or WebGL (not DOM nodes — a 128×64 hex grid with animation will choke on SVG at scale).
- **Auth:** simple session-based accounts; a country is bound to an account at game join.
- **Ops:** Docker Compose for local dev (app + Postgres). One command to bring up a world from a config file.

Swap any of these only with a stated reason.

## User interface

**Map view** is the centerpiece. Hex grid, per-country coordinate origin at the capital, fog of war showing only what that country has explored or currently senses. Layers the player can toggle: ownership, designation, efficiency, mobility, road and rail networks (rail drawn as connected lines with depots marked and severed links flagged in red), a chosen commodity's stock level, and threat/radar contacts.

**Movement visualization** — the explicit ask. After each update, animate the flows the update produced: goods, workers, and civilians traveling sector to sector along distribution paths, as directional particle streams or animated arrows whose thickness scales with volume and whose color encodes the commodity. The player should be able to scrub through the last update's flows and immediately see where their logistics are choked. Also render in-progress unit movements and queued manual moves.

**Sector inspector** — click a sector: designation, efficiency, population, stocks vs. thresholds, projected production at next update, and what it is short of.

**Command console** — keep a text command line alongside the graphical UI, with the original's verbs (map, census, des, move, dist, thresh, prod, budget, expl, tele, wire) and readline-style history. Veterans will live in it; newcomers will use the panels. Every console command and every panel action must go through the same command API — no divergent code paths.

**Nation dashboard** — budget projection for the next update, tech/research/education/happiness levels, BTU balance, countdown to next update.

**Comms** — telegrams (private, country to country), announcements (public), and a news feed of world-visible events.

## Agent (AI) players

A country may be controlled by a human or by a software agent. Agents exist first to exercise the engine — a world that can run twenty bot countries through five hundred updates unattended is a world whose economy you can actually balance — and second as a comparative testbed for different language models playing the same game under identical rules.

**Hard requirement: agents are ordinary players.** They authenticate as a country, they see only what fog of war permits that country to see, they spend BTUs at the same rate, and they issue commands through the same command API as the web UI. No engine back door, no god's-eye state, no extra actions. If an agent can do something a human cannot, the comparison is worthless and so is the balance data.

**Adapter interface.** Define AgentController in its own module with one method: given a serialized turn context, return a list of commands. Implementations:

- ScriptedAgent — a fixed opening book. Fast, deterministic, the default for engine regression tests. Build this first; most testing needs nothing more.
- HeuristicAgent — hand-written rules (keep food positive, build efficiency, expand toward high-fertility neighbors). The baseline that language models must beat to be interesting.
- LlmAgent — a thin adapter over a chat completion endpoint, with per-provider subclasses (Anthropic, OpenAI, xAI, etc.) selected by config. Provider credentials come from environment, never from the config file.

**Turn context.** Serialize the country's own view — map, census, commodity stocks, production forecast, budget, BTU balance, telegrams, announcements, recent update diff, and the legal command grammar — into a compact structured document. Give the agent a scratchpad field that persists across turns so it can keep notes; it has no memory otherwise. Cap the context size in config and truncate oldest-first.

**Command validation.** Agent output is parsed into the same command objects the console produces and validated identically. Invalid commands are rejected with an error the agent sees on its next turn. Malformed output costs a turn; it does not crash the update.

**Diplomacy and communication.** Agents send and receive telegrams and announcements exactly as humans do, at the same BTU cost, with no separate channel. Treaties, trade offers, threats, alliances, and betrayals are all in scope, and an agent may lie — the engine enforces the rules of the world, not the honesty of its inhabitants.

Critically: **no player is told which countries are agents and which are human.** Country rosters expose names only. An agent's turn context must not contain a controller flag for itself or anyone else, the tournament comparison table must not be visible in-game, and the web UI must not badge bot countries for human players. A human should be able to negotiate a border treaty for three weeks without knowing whether the counterparty is a person. That ambiguity is the most interesting property of this whole design and it is trivially destroyed by one careless field in a JSON response.

Two consequences to handle deliberately:

- **Inbound text is untrusted.** Telegrams and announcements written by other players arrive inside an agent's context window. Delimit them unambiguously as third-party player speech, never as instructions, and state in the agent's system framing that message content is in-game communication from rivals. The real defense is structural: every command an agent emits passes the same validation as a human's, so no message can talk an agent into an illegal action. It can talk one into a legal but catastrophic action — but that is not an exploit, that is diplomacy, and it should be logged and celebrated.
- **Log the whole conversation.** Every telegram and announcement, with sender, recipient, update number, and full text, in the same audit log as commands. The transcript of how four models negotiated, allied, and turned on each other is likely to be more interesting than the final scores.

**Scheduling.** Agents act between updates, not during them. The scheduler wakes each agent once per update window, with a wall-clock timeout and a retry budget from config. A slow or failed agent simply does nothing that window — the world does not wait.

**Reproducibility and comparison.** Log every agent turn: context sent, raw response, parsed commands, commands accepted, BTUs spent, wall-clock and token cost. That log plus the world seed makes a game auditable and, for scripted and heuristic agents, exactly replayable. Ship a tournament mode: run the same world seed and config N times with a given roster of agents and emit a comparison table. Because open diplomacy makes any single run's outcome largely a story about who allied with whom, tournament mode must support a **diplomacy: off control arm** — identical roster and seed with communication disabled — so economic competence can be measured separately from negotiation. Report both; the gap between them is the interesting number.

**Scoring.** Do not score on BTUs. BTU accumulation is a means, and an agent optimizing for it will correctly learn to sit still and do nothing, which is a bug in the metric rather than a strategy. Score on a configurable composite of civilian population, total sector efficiency, tech level, treasury, and territory held, with survival and territory dominating in games where units are enabled. Report BTU spend as an efficiency statistic — score per BTU — not as the objective.

## Handicaps

Any country, human or agent, may carry a handicap: a set of multipliers applied to its own rates and nothing else. Use them to give a novice a fair game against veterans, to compensate a slow agent, or to isolate a variable when comparing models.

```yaml
handicap:
  btu_rate: 0.80              # accrues BTUs at 80% of normal
  btu_cap: 1.00               # ceiling on banked BTUs
  production: 1.00
  mobility: 1.00
  research_rate: 1.00
  starting_commodities: 1.00
  command_budget_per_update: null   # optional hard cap on commands issued
```

Defaults are 1.0 across the board. Multipliers apply at the point of accrual inside the update, are visible to the country that carries them and to the administrator, and appear in the tournament comparison table so results are never quietly confounded. An administrator can adjust a handicap mid-game; the change takes effect at the next update and is written to the news feed.

### Calibration protocol

The handicap dial exists to answer a specific question: **what handicap does an agent need before a strong human player is even with it?** Support that as a first-class workflow.

- **Session-window cap.** Provide handicap.session_windows_per_day — a limit on how many distinct times per day an agent may act, regardless of update frequency. Without it a bot wins on uptime rather than judgment, which measures nothing. Default agents to three windows a day, roughly what an engaged human sustains.
- **Bisection harness.** Given a fixed roster, world config, and a target win rate, run repeated series while bisecting a chosen handicap parameter until win rates equalize, and report the converged value with a confidence interval. Human-in-the-loop series obviously run at wall-clock speed; agent-only series run headless at full speed.
- **Store results as a versioned benchmark.** Record model identifier, config hash, converged handicap, sample size, and date, so the same calibration can be re-run against each new model release and the numbers compared over time.
- Report both the diplomacy-on and diplomacy-off arms, since a model may need a different handicap depending on whether negotiation is available.

## Administrator tools

World creation wizard driven by the config file; add/remove countries before start; pause, resume, and force an update; adjust the schedule mid-game; a read-only god's-eye map; and an update log with per-country diffs.

## Build order

1. **M0** — Engine skeleton: world model, config loader with validation, world generator, deterministic update with production and consumption only. Plan-then-apply resolution and the direction-symmetry tests. Simulation harness, ScriptedAgent, and balance charts. No UI, no persistence beyond a JSON dump.
2. **M1** — Persistence, accounts, single-player-visible map, sector inspector, designation and basic movement. Manual update trigger.
3. **M2** — Distribution, thresholds, mobility, roads, budget. Scheduled updates. Flow visualization.
4. **M3** — Multiplayer: fog of war, contact, telegrams, announcements, BTUs, handicaps, admin tools. HeuristicAgent and the LlmAgent adapter; tournament mode.
5. **M4** — Tech, research, education, happiness; sector efficiency build-up; rail networks and depots; the full sector type table.
6. **M5** — Units, combat, missions.

Do not begin a milestone until the previous one's tests pass and a human has played it.

## Engineering standards

- The engine module has no I/O and no framework dependencies. Everything else may.
- Every rule that a player could reasonably want to tune lives in config, not in code. If you find yourself typing a numeric literal into engine logic, it belongs in the config schema.
- Property-based tests on the update function: conservation of commodities except at documented sources and sinks; no negative stocks; determinism given a fixed seed.
- Golden-file tests: a fixed world plus a fixed config plus N updates yields a byte-identical state hash.
- Document the update order of operations in docs/update-sequence.md before implementing it, and keep it accurate.

## First response

Do not write code yet. First produce: (1) the config schema as commented YAML, (2) the update sequence document, and (3) the module and package layout. Flag any place where you are guessing at original Empire mechanics rather than knowing them, so I can decide the rule myself.
