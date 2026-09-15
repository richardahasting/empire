# Where we are, and what is next

A running note of the state of play, so a session can pick up without re-deriving
it. Ordered work lives in `m5-plan.md`; this page says how far along it is and
where the open decisions sit. Last updated **2026-09-15**.

## Landed 2026-09-15: what the levels cost, forests as parks, a workforce as happy as it is

- **Education, compared with the original** (#215, closed): the rule is line for line the original's; Wolfy's
  schools were starved of lcm. Its upkeep is now on the dashboard, points made against points to hold the level
  (#226); holding 44 takes ~1,450 points an update per 100,000 civilians. Richard kept the rest as the original
  had it, including education discounting happiness ("we've never been more educated, and never less happy").
- **A forest doubles as a park** (#227): its lcm, plus half a park's happiness per worker at $0.25 a point, and
  500 people to a park's 1,000. `production_cash_by_output` prices one output of a sector type separately.
- **Happiness: +1% work a point, no ceiling** (#230). The bonus stopped at 20 (×1.2). The guides wrongly said
  unhappy countries do half the work.
- **Fixes:** a hand leg cut short for fuel ends in port and the order resumes (#213); the console API takes
  `command` as `line` and refuses anything else with a 400 instead of a silent no-op (#225).
- **Closed as already fixed or declined:** #185 (done in #186), #195 (Reload ship rules), #214 (the same
  military-pay and interest causes as Rick's), #216 (Richard: scrap stays a plain command).
- **Game 82 hand edits**, in `deity_edit`: the forest type from the preset (Rick's ten forests went from 1,000
  people to 500), and the happiness ceiling removed from its rules.

## Landed 2026-09-14: ships that do not strand, landings, radar, money as the original paid it

Nearly all of it found by playing game 82, Richard's test-every-feature game, and by
the Wolfpack agent's playtest of it.

- **Fuel that cannot strand a fleet.** A docked ship does not leave until her tank is
  full (#176/#177). **A ship never sails further than she can get back from** (#181):
  every leg, in port or at sea, stops where the fuel left still reaches the nearest
  harbour by sea with a quarter to spare, and a ship out of reach makes for that
  harbour (by sea, not as the crow flies, #184). A boat in a strange harbour goes home
  to fish (#188).
- **Tenders** (#182/#183/#189/#190). A ship at sea without a hex of fuel calls; the
  nearest free tender answers, fills her tank, patches her hull to the limp line, and
  goes home to wait on call.
- **Assault ships and landings** (#193/#194/#206/#207). `assault_ship` carries 100
  mil and 20 civ. `land SHIP x,y` takes unowned coast; at war, enemy coast is an
  assault fought man for man against the sector's and its neighbours' military.
- **Radar works** (#208/#209). A station reaches 12 × efficiency (+ height) × tech,
  capped at 30, reveals the map within it, and draws its ring. It had done nothing.
- **Updates 35–45× faster** (#210/#211). Game 82's engine update went from ~2.3 s warm
  (6.5 s cold) to ~50 ms (180 ms): distribution was re-pricing every hop of every
  route. Golden hash unchanged.
- **The web client**: shift-drag selects many sectors for one order (#191/#192);
  the readout says where the surplus goes, `Surplus ↔ w (-10,-4)` (#179/#180).
- **Deity: Reload ship rules** (#186) takes `units.ships` from the preset and nothing
  else, because a full reload would have cost game 82 about 32,000 people to
  population ceilings it never had.
- **The Wolfpack playtest batch** (#196–#205, PR #212). The quay (harbour plus
  dockside warehouse) refuels, crews and builds; a build refusal names the nearest
  stock; a `sail` **pauses** a standing order, which resumes on arrival; a full
  enlistment centre makes military again; census shows pet, gun and shell.
- **Demobilize** (#217/#218). `demob SECTOR N | all | keep N`, on the sector menu and a
  dragged selection. Nothing could shrink an army before.
- **Money as the original paid it.** Bank interest was a hundredth of the original's
  `bankint` (#219/#220); tax now scales with sector efficiency (#221/#222). Both read
  from gefla/empserver `update/prepare.c`.
- **Game 82 hand edits**, all in `deity_edit` with the service stopped: two refuels of
  stranded ships, the enlistment centre at 124,21 made a mine, Rick's military cut from
  12,235 to 1,000, bank interest 0.25 in its rules, and its three banks' `bar`
  threshold to 10,000. Rick went from losing $31k an update to making about $280k.

## Landed 2026-09-13: the ships are finished

- **Ships phase 3: war at sea** (#68). `fire SHIP x,y [CLASS]` resolves now, and
  the target and everything of its country in reach answer in the same
  exchange. At the update, `CombatStep` (10a, after detection) has every armed
  ship and every fort or harbour with guns fire on a hostile ship it can see and
  reach, all from one state, applied at once.
  - **Hostile** means at war, or a ship marked for a peacetime shot at you, for
    `fired_upon_updates`. Nothing fires at peace by itself.
  - **Aiming:** a fresh contact or own sight. Submarines need a contact and a
    destroyer or frigate.
  - **Damage** comes off efficiency; a hull sinks at 10%; victors in her hex
    salvage cargo less a quarter.
  - **Supplies:** warships carry guns and shells as cargo, are built with them
    aboard, and rearm in harbour.
  - **Missions:** `patrol`, `search`, `escort`, `blockade` (stops hostile ships
    within 1) and `interdict` (shells enemy trains). Every mission comes home for
    shells, fuel, crew or repairs and goes back out.
  - **The web map** finally draws contacts, which it never had. The Fleet panel
    fires, searches and escorts.
  - Every number is a GUESS under `units.ships.combat` and `units.ships.missions`.
- **Limp home.** Sea wear stops at the efficiency a hull needs to make one hex an
  update, and a hull at or below it makes for the nearest harbour by herself, at
  least a hex an update, orders kept. Found in game 82, where two hand-sailed
  miners were worn to 4% and 0% and could never move again. The web Fleet panel
  also gained the missing "Mine from here" button.
- **Ships phase 2** (#67). Lanes feed thresholds and act on arrival; the `supply`
  mission keeps harbours topped up from wherever there is spare; tankers fill
  their own tanks from the hold; a per-ship logbook (`history SHIP`, the Fleet
  panel).
- **Between 2026-09-10 and 2026-09-13**, recorded in OpenBrain and the issues
  rather than retold here: map memory (#64), fuel (#65), crews (#66), rail lanes
  (#70), a sector holds ten thousand (#91), immediate navigation (#69), seabed
  mining (#112), relations (#137), news (#121), world-creation parameters, game
  deletion, adding countries, a separate test database (#115), and the
  first-evening playtest fixes (#146–#163).

## Landed 2026-09-09

Everything below is on `main` and deployed to `empire.service`.

- **Detection** (#75, PR #76). Radar stations and ships find enemy ships; a
  sighting becomes a `Contact` that remembers where the target *was*, ages, and
  goes stale. Submarines get their own low signature. `contacts` console verb.
  The first of three slices of #68 — combat and missions are the other two.
- **A sector holds a thousand** (#78, PR #80). Capacity back to the original's
  1000. Warehouses store ten times; a harbour stores food like a warehouse. A
  docked ship works any `dockside` sector next to its harbour — the warehouse
  next door. Ship holds unchanged; the ×10 storage is what makes them work.
- **Trains run on mobility** (#79, PR #80). Distance is what the sending sector's
  mobility pays for, hop by hop, and rail level prices a hop. `max_sectors_per_update`
  survives only as a runaway ceiling.
- **Choose the map** (#81, PR #83). `width`, `height` and `water` at game
  creation, overriding the preset. Up to 2,097,152 sectors.
- **The update stopped hashing itself** (#82, PR #83). The state hash is lazy and
  now opt-in (`options.state_hash_per_update`, default off) — it was written to
  the update log every update and read by nothing.
- **Whole units** (#77 first slice, PR #84). `Stocks` holds `short[]`; every
  quantity in the world is an integer; conservation holds exactly rather than
  within a tolerance.
- **Integer sector state** (#77, the rest). `Sector` is a class of narrow fields
  rather than a record of wide ones; conservation is checked by integer equality;
  the ledger and the update's neighbour table stopped being made of doubles and
  objects. See below for what it measured.

## Measured, on this box, 40 countries

Take these with `SimRunner --mem`, which reports heap in use after a full GC:

```
java -Xmx8g -jar empire-sim/target/empire-sim.jar --preset classic \
     --width 512 --height 1024 --countries 40 --updates 3 --seed 7 --mem
```

Before and after #77, same command, classic preset, 512×1024 = 524,288 sectors:

| | Before | After |
|---|---|---|
| heap, world as generated | 304 MB (610 B/sector) | **36 MB (73 B/sector)** |
| heap, after 3 updates | 401 MB (803 B/sector) | **69 MB (138 B/sector)** |
| 5 updates, wall clock | 124 s | **103 s** |

And the largest world the generator will make, 1024×2048 = 2,097,152 sectors: **128 MB
as generated, 253 MB after an update** (64 and 127 B/sector). That used to want the
whole 8 GB heap; it now fits in a quarter of one.

The per-sector number rises with play because a sector that has been settled holds
stock, and a sector nobody has touched shares one canonical empty `Stocks`. 73 B/sector
at generation is the whole heap divided by the sector count, so it includes the ships,
the country list and the JVM's own furniture — the sector object itself is 56 bytes.

`empire.service` runs `-Xmx8g`. Most of the wall-clock in the sim harness is the
scripted agents' turns, not the update — a real server pays that per player request
instead.

## #77 is done

`Sector` is 56 bytes; 120 with stock; 272 carrying stock, thresholds and delivery
orders at once. The issue's target was ~230 for a loaded sector and it landed close,
but the number that mattered turned out to be a different one: most of a large world is
ocean and wilderness holding nothing, and those sectors now share a single all-zero
`Stocks`, a single all-unset threshold array and a single empty `DeliverOrders`, so they
cost 56 bytes each and nothing more.

| Field | Was | Is |
|---|---|---|
| `Coord at` | 24 B + ptr | two shorts, `Coord` built on demand |
| `Coord distCenter` | 24 B + ptr | two shorts, `Short.MIN_VALUE` for unset |
| `Resources` | 40 B + ptr | five inline bytes |
| `terrain` | enum ref | byte ordinal against a cached `values()` |
| `designation` | String ref | byte id into an append-only global table |
| `elevation` | 4 B | short |
| `owner` | 4 B | byte |
| seven 0–100 scalars | 56 B | seven bytes, half-up |
| `thresholds` | `double[]`, 132 B | `short[]`, exact, shared when empty |
| `deliver` | 228 B | `byte[]` + `short[]`, shared when empty |
| `stock` | (done in PR #84) | shared when zero |

Beyond the issue's table, and worth more than several rows of it:

- **`Ledger.stock` is `int[][]`** and the tallies are `long[]` — 43 MB an update at a
  million sectors rather than 86, and the tallies are exact, which is what lets
  conservation be checked by equality.
- **`Ctx.neighbours` is a flat `int[]`.** It was a `List<List<Coord>>` rebuilt every
  update: six `Coord` objects and two lists per sector, over 200 MB of garbage an update
  at a million sectors — more than the world weighs.

Rules that came with it, all Richard's, all in the issue:

- A level is bought in whole points or not at all. Materials accrue until a whole point
  is affordable rather than being spent on a fraction that rounds away.
- `road.decay_per_update` and the radar build's were 0.5, which against a byte level
  rounds back to where it started; both are 1.0. That was the whole of the config audit
  — every other sub-1 rate is a multiplier or cash.
- A hand move moves whole units and its mobility cost rounds **up**, not half-up.
  Half-up would make any move costing under half a point free, and free repeats.
- Conservation is integer equality. The old 1e-6 relative tolerance would have hidden a
  several-unit leak in a large world.

Golden hashes moved, as the issue said they would: the teaching 3×20 golden was
regenerated deliberately.

## And then the update stopped walking the map (#87)

Packing the sectors made a second problem visible: at 512x1024 with forty countries,
three updates in, **350 sectors of 524,288 are owned**. Every step already skipped the
rest — the work was scoped right all along. The update was simply walking past 523,938
irrelevant hexes ten times over and rebuilding every one of them.

| Step | Before | After |
|---|---|---|
| ctx | 59 ms | 15–33 ms |
| accrual, buildup, production, money, levels | 19 ms | ~0 |
| population | 8 ms | 4 ms |
| flow | 520 ms | ~20 ms |
| apply | 300 ms | ~25 ms |
| **update** | **~900 ms** | **~78 ms** |

The largest world the generator will make, 1024×2048 = 2,097,152 sectors, updates in
**~280 ms**.

**The golden hash did not change.** That was the acceptance test, not a nuisance:
iterating a sorted index of owned sectors has to visit them in the same order a full
walk did, or the treasury runs dry on a different sector.

What it took, in descending order of value:

- **`FlowStep.path` allocated three world-sized arrays per call**, four hundred calls an
  update — 2.6 GB allocated and filled to search a few hundred sectors. The scratch is
  reused and reset by what it touched. Alone: flow 520 ms → 43 ms.
- **The neighbour table was rebuilt every update** though it depends only on the map's
  shape. Cached in `NeighbourTable`, and built with allocation-free arithmetic
  (`Hex.neighbourIndex`) instead of ~30 objects a sector.
- **`Ctx` builds the worklists** — owned, populated, withHeld, activeUnowned — in one
  ascending pass costing 1.13 ms. Ascending is load-bearing.
- **`FlowStep.carryHeld` wrote `heldNext` for every sector**, so `ApplyStep`'s
  "unchanged" guard had never once fired in the life of the code.
- **`ApplyStep` reuses untouched sectors**, and conservation sums only what it rebuilt —
  a skipped sector adds the same amount to both sides and cancels.

## Then commands stopped copying the world (#89)

With the update down to ~78 ms, command execution was the whole cycle. Timed properly —
`Sim`'s own agent timer spans the agent's turn *and* its commands, so an earlier reading
of "agents ~12 s, commands ~7 s" was one number split by guesswork — 512x1024, forty
countries, five updates:

| Phase | Before | After |
|---|---|---|
| `CountryView.of` | 366 ms | ~590 ms |
| `agent.turn` | 14 ms | 13 ms |
| **command execution** | **44,547 ms** | **296 ms** |
| `Update.run` | 813 ms | ~570 ms |
| **per update cycle** | **~9.1 s** | **~0.30 s** |

Two causes, and the second was much the larger:

- **`World.withSector` copied the sector list, twice** — `new ArrayList<>(sectors)` and
  then `List.copyOf` in the record's compact constructor — to change one hex. 10.1 ms a
  call. `Sectors` now holds them in 1024-sector chunks and changing one copies the chunk
  array and one chunk, sharing the rest: 1,536 references instead of 524,288.
- **Every command built a `Ctx`.** Six verbs make one to ask what a hop costs, and its
  constructor allocated a `Ledger` — `int[nSectors * nCom]`, 29 MB — plus the work pool,
  the held-parcel slots and #87's worklist scan. All of it is lazy now. Only the update's
  steps write a ledger, and `Ctx`'s own reads of pending stock answer 0 when there is
  none rather than conjuring one, so `maxPopulation` no longer allocates 29 MB to add
  zero. Command execution: 1,103 ms an update → 59 ms.

Also, the sim harness was walking the world once per country in `Sim.row` and again in
`Scoring.score` — eighty scans of half a million sectors an update. One pass now.

### Where the time is now

`CountryView.of` is the largest single phase, at ~2.9 ms a country and one call per
country per update. It scans the world to find what a country owns and can see, which is
the same shape of problem #87 fixed inside the update — worth a look before anything
else.

Parallelism is still not the lever. If it ever is, shard by **nation**, not by sector:
the ordering that matters (`BuildUpStep` spending a treasury until it runs dry) is
intra-country, so nation-sharding preserves it exactly where sector-sharding breaks it,
and #77 making the tallies `long[]` means per-thread partials merge identically.

## Not done, and deliberately

- **`held` is still a `List<HeldParcel>`.** An empty list is a shared singleton, so it
  costs a pointer; a sector with cargo in transit pays for the cargo, which is fair.
- **Ships and countries were not touched.** There are a few thousand of them, not a
  million.

## Open decisions

- **Rail may be too strong.** At rail 100 a 300-unit train spent 11 mobility over
  9 hops, ~1.2 a hop against a 127 budget — extrapolating to roughly 100 hops in
  one update, hitting the ceiling rather than running out. `mobility_multiplier`
  (1.0) is the dial.
- **Rail throughput vs the cap.** `capacity_per_update_at_100` is 5000 into
  sectors that hold 1000.
- **Depots confer nothing** but permission to run a train. Keep them only if they
  earn it — `depot_range_multiplier` / `depot_capacity_multiplier` were sketched
  but not built (#70).
- **Big hulls vs the cap.** A super tanker holds 6000 and can only deliver into
  ×10 storage. Works, but the sea economy has not been played.
- **`CountryView.reveal`** discloses a contact's exact hex, class and owner at
  every confidence band, so a faint smudge reads like a firm fix (#75, TODO in
  the code).

- **Combat numbers are unplayed.** A destroyer settles an unarmed freighter in
  three or four updates and has six salvos in her magazine; a battleship sinks a
  destroyer in two or three. Coastal guns are strong (20 guns). Tune
  `units.ships.combat` after a real war.
- **Old games have old ship rules.** A game's rules are fixed at creation, so a game
  created before a ship feature (combat 2026-09-13; tenders and assault ships
  2026-09-14) needs the deity's **Reload ship rules** to get it.
- **Old games keep the old bank interest.** Interest is a rule (0.0025 in any game
  created before 2026-09-14); only game 82 was changed by hand. Tax by efficiency is
  engine logic and reached every game at once.
- **Bank interest is now the original's, and it is large.** $15 a bar an update at 60
  ETUs, 10,000 bars to a bank: $150k an update per full bank, against $0.50 a
  civilian. Unplayed at that rate; the original had a market and loans to spend it on.
- **Still not the original:** a city's population ceiling does not rise with its
  efficiency; no reserves, market, loans or trade-ship payouts. (Captured civilians'
  quarter tax came with unrest, #72.)
- **Agents do not know the new verbs** (`supply`, `fire`, the missions, `land`,
  `demob`); the scripted agent is a fixture and is not taught strategy.
- **Unrest (#72) is the original's**, built 2026-09-15: loyalty, work, revolt, guerrillas, `anti`,
  capture at loyalty 50 with a quarter tax. Unhappiness alone rarely bites at 60 ETUs (the original's decay);
  conquest and hunger do. Security land units against guerrillas wait for #71.
- **Land warfare and planes (#71)** have a plan for review in `m5-land-air-plan.md`.
- **Dead config:** `levels.curves` (`research_to_tech`, `education_to_research`) in schema.yaml is read by
  nothing.

## The standing caveat

Capacity 1000, whole-unit flows, longer rail reach and the new world sizes are
**live but unplayed** — only test fixtures have exercised them. A running game
keeps its own config snapshot for life, so game 14 still plays by the old 9999
rules and none of this is visible there. Judging any of it needs a fresh game.

#77's whole-point building joins that list, and it is the one most likely to want
tuning. A sector that could afford 0.7 of an efficiency point an update now builds
nothing and keeps its materials, where before it built 0.7. Small sectors therefore
climb in visible steps rather than a smooth ramp, and a sector that can never afford a
whole point in one update stalls until it accumulates enough — which is the intended
rule, but nobody has watched it play out over sixty updates. The road-decay probe was
the only test that noticed the change at all.

## Parked

- **"Get some players"** — the thread that opened and never closed. It could mean
  making a game joinable by a stranger, getting a real multiplayer game running,
  or sharpening the agent tournaments. Those pull in different directions.
