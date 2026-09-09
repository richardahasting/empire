# Update sequence

The update is a pure function:

```
update(WorldState snapshot, GameConfig cfg, long updateSeed) -> UpdateResult { WorldState next, Diff diff, Events events }
```

No wall clock, no I/O, no database. The server obtains the snapshot, calls
this, and writes `next` and `diff` in one transaction. A failed update throws;
nothing is written; the schedule pauses and the deity is paged.

Every step below reads only from the **snapshot** and writes only to a
**delta ledger**. The ledger is applied once, at the end (step 12). That is
what makes the update direction-independent: no step can observe another
step's partial writes, and no step can observe the results of its own writes
for sector *A* while processing sector *B*.

Per-ETU rates in config are multiplied by `cfg.schedule.etus_per_update`
exactly once, at the top of the step that uses them. Steps never loop over
ETUs; the ETU is a unit, not a sub-tick. (**GUESS:** the original *did* loop
per ETU for some things, notably population growth compounding. This design
uses closed-form compounding, `pop * (1+rate)^etus`, which is equivalent for
growth and cheaper. If the original's per-ETU interleaving of eating and
breeding matters to you, this is the place to say so.)

## RNG discipline

One master seed per update. Each step that needs randomness derives its own
stream: `rng(step_name, updateSeed)` (SplittableRandom seeded by a hash of the
pair). Within a step, the stream is consumed in **canonical sector order**,
defined as ascending `(y, x)` **after** the world has been normalised by the
rotation-symmetry test's canonical frame. Because the ledger is only applied
at the end, consumption order affects only which sector gets which random
draw, never who wins a contested resource; that is decided in step 7 by the
contention rule, with RNG as the last-resort tiebreak.

## Order of operations

The numbers are the step ids used in the ledger and in the per-update log, so
a diff can be attributed to the step that produced it.

### 1. Snapshot and normalise
Freeze the world. Build the per-country sector index, the adjacency graph, the
rail network graph (contiguous rail chains between depots, with any segment
below `infrastructure.rail.min_level_to_carry` treated as absent), and the
ownership map. Apply any **pending administrative changes** scheduled for this
update (handicap edits, schedule edits) — they are inputs, not effects.

### 2. Accrual
Things that grow just by time passing, in no particular order because they do
not interact:
- **Mobility**: each sector gains `sector_accrual_per_etu * etus * f(eff)`,
  where `f(eff) = floor + (1 - floor) * eff/100` with `floor =
  accrual_efficiency_floor`. The floor dates from when mobility was debited from
  transited sectors: a 0% sector with 0 mobility could never be entered, so it
  could never receive the materials to build efficiency, so it would stay at
  0% forever (found by the M0 harness on 2026-09-07). With the sending sector
  paying (the default since 2026-09-08) that trap is gone; the floor stays
  harmless. Capped at `sector_max`,
  times the owner's `handicap.mobility`.
- **BTUs**: each country gains `accrual_per_capital_civ_per_etu * capital_civs
  * etus * handicap.btu_rate`, capped at `btu.max * handicap.btu_cap`.
  (**KNOWN:** only the *active* capital counts.)
- **Sanctuary**: nothing special; sanctuary sectors update like any other if
  `players.sanctuary.update_runs_while_in_sanctuary`.

### 3. Population
Per sector, from snapshot stocks:
1. **Subsistence, then eating.** The first `subsistence.civs_per_sector`
   people (× fertility/100 when `scale_by_fertility`, in `applies_to` order:
   civ, uw, mil) live off the land: they consume no stock and cannot starve.
   (**KNOWN:** the original let a few hundred civilians per sector forage.
   Richard, 2026-09-07: "people don't starve unless they have a gun to their
   head.") Only the people beyond the limit have demand =
   `excess * food_per_*_per_etu * etus`. If food ≥ demand, food -= demand.
   Else starvation: each class loses `min(shortfall_fraction,
   starvation_max_fraction_per_update)` of its *excess* (**GUESS:** original
   starved civs first, then mil? I am not sure.), and food goes to 0.
2. **Births.** civ and uw grow by closed-form compounding, bounded by the
   sector's population ceiling (`max_population * eff/100 * research curve`),
   consuming `food_per_birth` each; births that cannot be fed do not happen,
   except that births under the unused subsistence limit need no stock.
3. **Plague** (if `options.plague`): roll per sector with the `plague` stream;
   mortality is applied to next-state population and a `PlagueEvent` emitted.

Population changes are written to the ledger. Work in step 5 uses **post-
population** numbers (**GUESS:** original used the pre-eating census for work;
I have chosen post-eating so starving sectors produce less, which is the more
legible rule. Flag if you want the original.)

### 4. Efficiency and infrastructure build-up
Per sector, in parallel (they draw from different budgets):
- **Efficiency**: if the sector has a designation and `eff < 100`, spend
  `sector_type.build` materials from the sector's own stock and
  `efficiency.work_per_point` work per point, up to
  `max_points_per_etu * etus`. Limited by min(materials, work, cash).
- **Road / rail / radar level**: same shape, using `infrastructure.*.build_
  materials_per_point`, `work_per_point`, `max_points_per_update`, terrain cost
  multiplier and terrain cap. Rail additionally requires country tech ≥
  `tech_required`; bridge/tunnel sectors require the one-time materials.
- **Decay**: every road/rail/radar level loses `decay_per_update` unless the
  owner pays `maintenance_cash_per_point_per_update`; the country pays as
  much maintenance as it can afford in **canonical sector order** with the
  remainder rotting. (**NEW.** The order matters only when the treasury is
  short; it is deterministic and documented, which is the bar.)

**Work pool.** A sector's work for the update is `(civ*per_civ + uw*per_uw +
mil*per_mil) * etus * happiness_curve`, in work-unit·ETUs, using the post-step-3
population. Step 4 spends from it (`work_per_point` per efficiency point) and
step 5 gets the remainder. Production rates in config are per work-unit per
ETU, so step 5 multiplies by the pool directly, not by `etus` again.

### 5. Production
Per sector with a producing designation **at or above
`efficiency.production_min_efficiency` (60, KNOWN)**:
```
work_avail   = (civ*per_civ + uw*per_uw + mil*per_mil) * happiness_curve - work_spent_in_step_4
output_cap   = work_avail * produces[c] * (eff/100) * resource_gate/100 * level_curve(level) * handicap.production
input_cap    = min over consumes: stock[input] / consumes[input]
produced     = min(output_cap, input_cap, capacity_remaining)
```
Inputs are consumed in proportion; output is added. Level-producing sectors
(`produces_level`) add to the **country** level in the ledger, scaled by the
education/research cross-curves. Enlistment converts civ → mil. Nothing in
this step moves anything between sectors. (**KNOWN:** the three-way min is
the original model; **GUESS:** every constant.)

### 6. Plan flows
This is the step that replaces the scan-order loop. Build a demand list from
the snapshot **plus the ledger so far** (so a sector that just produced food
offers it; one that just ate wants it):

- **Deliver orders** (KNOWN: deliver.c; issue #45). For every sector with a
  standing order for a commodity, if its post-production stock is above the
  order's threshold and the hex in the order's direction is the same country's
  land, plan a one-hop flow of the excess there. The receiver's own orders act
  at the *next* update, so a chain advances one hop per update — the price of
  not depending on scan order. Mobility: the sender pays, ÷
  `distribution.deliver_mobility_bonus`.
- **Distribution.** For every sector with a distribution centre, for every
  commodity: if `stock < threshold`, it is a **sink** for `threshold − stock`
  from the centre; if `stock > threshold`, it is a **source** of
  `stock − threshold` to the centre. Route is the cheapest-mobility path
  (Dijkstra over adjacency with edge weight = destination sector's
  `move_cost_by_terrain * efficiency_discount * road_discount(road_level)`),
  truncated to the country's **reach** (`distribution.max_reach_sectors`,
  road bonus prorated along the path). Per-unit mobility is the packed weight
  (`lbs / packing` of the sector the goods leave, KNOWN) × the cost to enter
  each sector, **÷ `distribution.mobility_bonus` (10, KNOWN)**; hand moves pay
  full price.
- **Rail shipments** issued as commands since the last update (they were
  validated for connectivity at issue time; re-validate now against the
  step-1 rail graph, and any whose route is now broken become **stranded
  parcels** at their current position). Route edges are depot-to-depot rail
  lines, capacity `capacity_per_update_at_100 * min(endpoint depot eff)/100
  * rail_level/100`, range `max_sectors_per_update`.
- **Held parcels** from previous updates (partial deliveries, stopped trains)
  re-enter the plan from their current sector with their original
  destination and remaining reach reset.
- **Manual moves** queued since the last update, with the range-and-hold rule.

Each planned flow is a tuple
`(commodity, qty_requested, path[], mobility_cost_per_unit_per_hop[], reach)`.

### 7. Resolve contention
Three budgets can be over-claimed: a **source's stock**, a **paying sector's
mobility** (the sender's, or each transited sector's under
`mobility_debited_from: transited_sectors`), and a **rail line's capacity**.
Resolve iteratively:

1. For every over-claimed budget, scale each claim by `budget / total_claims`
   (**proportional to demand**).
2. Where two claims tie for the last indivisible unit, prefer the lower
   `commodity.priority`.
3. Where they still tie, draw from the `contention` RNG stream.
4. Re-check budgets (scaling one claim can free another); repeat until no
   budget is over-claimed. Terminates because claims only shrink.

**Continuous by default.** With `distribution.quantum` unset, quantities are
real numbers and proportional scaling is exact, so rules 2 and 3 never fire and
the whole step is exactly rotation-symmetric. Setting a quantum (e.g. 1.0)
ships whole units; the last indivisible unit at each over-claimed budget then
goes by commodity priority, then seeded RNG. That tiebreak is deliberately
*not* rotation-symmetric (it cannot be), which is why the symmetry tests run
with the default.

Then walk each flow along its path hop by hop, debiting each hop's cost from
the sending sector (`mobility_debited_from: sending_sector`; a resumed held
parcel's sender is the sector holding it) until either the parcel arrives, its
reach is exhausted, or the payer's mobility hits zero. Under
`transited_sectors` each entered sector pays its own hop instead. Whatever remains becomes a **held parcel** in the last sector
reached, recorded in the ledger with its destination, and rendered by the UI
as an arrow that stops short. Nothing is destroyed or teleported. Parcels in
the same sector with the same commodity, owner and destination merge, so a
choked route produces one growing parcel rather than a pile of slivers.
Remainders below 1e-9 are floating-point dust and are delivered, not parked.

**People in transit go hungry until they arrive** (Richard, 2026-09-07).
A held parcel is not in any sector's stock, so civilians, military and workers
on the road neither eat, nor breed, nor work, nor die, until the parcel lands.
This is the rule, not a loophole: the cost of a choked route is that the people
on it contribute nothing while they wait.

The direction-symmetry test (six identical chains, six directions, identical
results) and the rotation test (rotate world 60°, output rotates) are
assertions on the output of *this* step plus step 12.

### 7b. Rail (**NEW** by spec; runs inside step 7 after road flows)
Trains: every pending rail order and every parked train re-plans a
fewest-hop path over **rail-capable** sectors (owned, land, `rail_level ≥
min_level_to_carry`) between **working depots** (depot designation at the
production minimum efficiency, with track). No path: a new order is
cancelled with an event; a parked train is **stranded** in place with an
event and waits for a repair. Each rail sector has a capacity budget per
update (`capacity_per_update_at_100 × rail_level/100`) shared proportionally;
what leaves is scaled by the lower endpoint depot's efficiency. A train
advances up to `max_sectors_per_update` and **holds on the rail sector it
reached** as a `HeldParcel` of mode `rail`, visible and capturable. Cash is
charged by volume. Rail moves spend no mobility.

### 8. Money
Country-level:
- **Income**: taxes on civ/uw, bank interest on bars (if `options.interest`).
- **Expenses**: military pay, infrastructure maintenance actually paid in
  step 4, rail shipment cash, efficiency/infra build cash actually spent.
- Apply `handicap` where the rate accrued.
- If treasury < `bankruptcy.threshold`, set the country's bankrupt flag for
  the **next** update (effects apply in steps 2/5 next time — never within the
  same update, to keep steps independent).

### 9. Levels (**KNOWN**, the original's `prod_nat()` + `age_levels()`)
Step 5 produced raw amounts of tech, research, education and happiness.
- **Tech and research are stocks.** Each update's production passes
  `limit_level`: above `easy` the gain is `easy + log_base(prod − easy + 1)`,
  capped at 250; it is added to the level. Both then **age** by
  `level × ETUs / (100 × level_age_rate)` (1 % per 96 ETUs).
- **Education and happiness are moving averages** of a per-ETU rate:
  `rate = produced × consumption / (civilians × ETUs)`, happiness also
  `× (1.5 − (E+10)/(E+20))`; the rate passes the flag-1 limit
  (`(rate − easy) / log_base(base + rate − easy)`), then
  `level = (level × average_etus + rate × ETUs) / (average_etus + ETUs)`.
  Stop producing and the level drifts back down over `average_etus`.
- **Technology bleed:** below a fifth of the leader's level, a 20 % chance
  per update to close a third of the gap (`techbleed` RNG stream).
- Ally sharing (`ally_factor`) is M3.

### 10. Detection
For each country, for each radar station / ship / plane, for each candidate
target within `range * 1.5`: compute `p` from the sigmoid, elevation,
tech, signature, terrain masking; roll from the `detection` stream. A hit
creates or refreshes a **contact** `(country, target-sector, what, confidence,
update_number)`. Contacts older than `contact_staleness_updates` are marked
stale; older than 2× are dropped. `held_cargo` uses its own low signature so
a raider does not see the haul. Adjacent owned sectors are always visible.
(**NEW** model; the original was binary range.)

### 11. Events and news
Collect everything the steps emitted (starvation, plague, bankruptcy,
stranded trains, severed rail, new contacts, sanctuary breaks, handicap
changes) into the world-visible news feed and per-country telegrams. Nothing
here mutates state.

### 12. Apply

**Sector notes** (issue #49). Every step that changes a sector appends a
plain-language line to that sector's notes in the ledger — "people ate 24
food", "efficiency +12% using 12 lcm, $12", "made 430 lcm using 430 iron",
"sent 430 lcm to 4,7 (surplus to centre)", "received 24 food from 4,7",
"12 civ over the population limit, lost" — in the order the steps ran. They
ride with the update log and are served per country, relativised, so a
player can ask any sector what happened to it last update.
Apply the ledger to the snapshot to produce `next`. Then the invariants:
- **Conservation**: for every commodity, `Σ next = Σ snapshot + produced −
  consumed − destroyed`, where produced/consumed/destroyed are the ledger's
  documented source/sink entries. Any other discrepancy is a bug and the
  update fails.
- **No negative stocks, no stock over capacity, no level outside 0..100,
  no mobility outside 0..max.** Civilians and workers above a sector's
  population cap are truncated here with an `overcrowding` event (**KNOWN**:
  the original's `trunc_people()` runs every update), so a sector cannot be
  packed past its cap by distribution or moves.
- **Determinism**: `hash(next)` is stored in the update log; the golden-file
  test re-runs and compares.

Emit `UpdateResult`. The server persists `next`, the diff, the seed, and the
step-attributed log in one transaction.

## What is deliberately *not* in the update

- Combat, unit movement, missions — M5; they slot in as steps 6b/7b (planned
  and resolved with the same contention machinery) once units exist.
- Command execution. Player commands (`des`, `move`, `dist`, `thresh`,
  `tele`…) execute **between** updates against the live state, cost BTUs at
  issue time, and are the only thing that mutates state outside this
  function. **Manual `move` is immediate** (Richard, 2026-09-08, as in the
  original): the goods land now and every sector entered pays its mobility
  now; if mobility along the route is short the quantity is capped to what
  fits and the rest stays at the source. Rail shipments are the exception:
  recorded at issue time, executed in step 6 with range-and-hold.
- Market and loans — options that, when enabled, run as their own step 8b
  (settle market orders, accrue loan interest). Schema slot reserved.

## Calibration probes (per the playbook)

Invariants prove the physics; probes prove the point. M0 ships at least:
- **Food chain probe**: agribusiness → warehouse → light manufacturing, 60
  updates; assert the manufacturing sector never starves and lcm output is
  within a band.
- **Direction probe**: the six-direction test above, plus a *reach* probe that
  a chain of length reach+1 delivers exactly reach hops and holds.
- **Rot probe**: a country with no cash watches road levels decline to zero
  within a predicted number of updates.
- **Nothing-happens probe**: a country that issues no commands for 100
  updates must still grow civs and accumulate BTUs, and must not go
  bankrupt from maintenance alone (the "sit still and do nothing" agent
  should be *boring*, not *dead*).
