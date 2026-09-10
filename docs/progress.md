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

## Measured, on this box, 40 countries

| World | Sectors | Heap | Per update |
|---|---|---|---|
| classic 128×64 | 8,192 | <1 GB | 0.35 s |
| 512×1024 | 524,288 | 2 GB | 17 s |
| 1024×1024 | 1,048,576 | 2 GB | ~45 s |
| 1024×2048 (max) | 2,097,152 | 8 GB | 82 s |

`empire.service` runs `-Xmx8g`. An update's own steps were ~11 s at 1M sectors,
of which ~9.5 s was the state hash; with it off they are ~1.8 s. Most of the
remaining wall-clock in the sim harness is the scripted agents' turns, not the
update — a real server pays that per player request instead.

## Next: finish #77

`Sector` is ~580 B. The target is ~200 B. Roughly a third is done.

| Field | Now | Target | Saves |
|---|---|---|---|
| `Stocks stock` | 44 B | **done** | — |
| `DeliverOrders deliver` | 228 B | two `byte[14]` | 144 B |
| `double[] thresholds` | 132 B | `byte[14]`, ×4 | 102 B |
| `Resources` | 40 B + ptr | five inline bytes | 39 B |
| seven `double` scalars | 56 B | bytes | 49 B |
| `Coord at` | 24 B + ptr | derive from the array index | 28 B |
| `Terrain` (enum ref) | 4 B | byte ordinal, cached `values()` | 3 B |
| `elevation`, `owner`, `designation` | 12 B | short + two bytes | 8 B |

In order of value against risk:

1. **`thresholds` → `byte[]`.** Already behind `threshold(i)`/`hasThreshold(i)`,
   and nothing writes it in place (checked). Needs a sentinel for "unset", since
   a byte has no NaN.
2. **`DeliverOrders` → byte arrays.** The biggest single item, and well
   encapsulated behind `has`/`dir`/`threshold`/`with`.
3. **`Resources` inline.** Five 0–100 values become five bytes and the object goes.
4. **Scalars → bytes.** Needs a config audit first: any sub-1 rate becomes a
   no-op under the whole-unit rule. `detection.radar.radar_level_build.decay_per_update`
   is 0.5 and would mean radar never decays.
5. **`Coord at`.** Most bytes per field but touches `s.at()` in ~100 places. Last,
   or never.

Not a `Sector` field, but worth more than several of the above: the **`Ledger`
still holds `double[nSectors][nCom]`** — about 86 MB an update at a million
sectors. Narrowing it to `int[][]` is safe now that every delta is integral.

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

## Parked

- **#68 combat and missions.** Richard, 2026-09-09: wait until there are players
  before building contact and battle.
- **"Get some players"** — the thread that opened and never closed. It could mean
  making a game joinable by a stranger, getting a real multiplayer game running,
  or sharpening the agent tournaments. Those pull in different directions.
