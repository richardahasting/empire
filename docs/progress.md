# Where we are, and what is next

A running note of the state of play, so a session can pick up without re-deriving
it. Ordered work lives in `m5-plan.md`; this page says how far along it is and
where the open decisions sit. Last updated **2026-09-09**.

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

- **#68 combat and missions.** Richard, 2026-09-09: wait until there are players
  before building contact and battle.
- **"Get some players"** — the thread that opened and never closed. It could mean
  making a game joinable by a stranger, getting a real multiplayer game running,
  or sharpening the agent tournaments. Those pull in different directions.
