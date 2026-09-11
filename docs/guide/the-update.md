# The update

Everything happens in the update. Between updates you issue orders; during one,
the world advances by `etus_per_update` **ETUs** (Empire Time Units) — 60 by
default.

## The one rule that explains most surprises

The update is a **pure function of the world as it was when the update
started**. Every step reads the *old* world and writes its results to a
scratch ledger. The ledger is applied once, at the very end.

Consequences, all of which catch people out:

- **No step sees another step's work.** A sector that produces food in step 5
  does not have that food when step 5 processes the sector next door.
- **Order on the map does not matter.** Sector 3,4 is not processed "before"
  sector 5,6 in any way you can observe. There is no scan order to exploit.
- **A chain of standing orders advances one hop per update**, not all the way
  along. The sector that receives goods this update acts on them next update.

If something did not happen that you expected, the usual answer is that it
depended on something else that happened in the same update.

## The steps, in order

### 1. Snapshot
The world is frozen. Rail networks, adjacency and ownership are indexed.

### 2. Accrual
Things that grow by time alone.

- **Mobility**: every sector gains `1.0 × ETUs` — a flat **60 per update** —
  capped at **127**. Efficiency does not affect it.
- **BTUs**: your country gains `0.0012 × capital civilians × ETUs ×
  capital efficiency%`, capped at **640**. Civilians in the capital count only
  up to 1,000. A 100%-efficient capital with 1,000 civilians refills the full
  640 every update; a half-ruined capital refills half as fast.

### 3. Population
Per sector, in this order:

1. **Subsistence.** The first `300 × fertility/100` people in a sector live off
   the land: they eat nothing from stock and **cannot starve**. An 80-fertility
   plain feeds 240 people for free; a 5-fertility mountain feeds 15. Order:
   civilians, then uncompensated workers, then military.
2. **Eating.** Everyone beyond that limit eats `0.0005 food per person per
   ETU` — 0.03 food each per 60-ETU update.
3. **Starvation.** If food runs out, each class loses a share of its *excess*
   proportional to the shortfall, capped at **half per update**. Order: uw,
   civ, mil. Food goes to zero.
4. **Births.** Civilians grow at `0.005 per ETU`, uw at `0.0025`, compounded
   over the update, each birth eating `0.006` food. Births that cannot be fed
   do not happen. Growth stops at the sector's population ceiling — flat per
   sector type (1,000 for most, 100 for mountain and wilderness), **not**
   scaled by efficiency.

### 4. Building up
A designated sector below 100% efficiency spends **half its available work** at
**one efficiency point per work unit**, plus the type's build materials and
cash.

A sector's work for the update is `(civ × 1.0 + uw × 1.0 + mil × 0.5) × ETUs`,
adjusted by happiness. So **1,000 civilians build a sector from 0% to 100% in a
single update**; 100 civilians gain 30 points. Whatever work is left over goes
to production in step 5.

Roads, rail and radar build the same way from their own budgets, and **decay**
each update unless you pay their maintenance. When the treasury cannot cover
everything, sectors are paid in a fixed, documented order and the rest rots.

### 5. Production
Only sectors at **60% efficiency or better** produce anything. Below that they
produce exactly nothing — this is the single most common cause of "my mine is
not working".

```
output = work_left × rate × (efficiency/100) × (resource/100) × tech_curve
input  = min over each input of (stock / amount_needed)
made   = min(output, input, room_left_in_the_sector)
```

`resource` is the sector's own endowment of whatever that designation depends
on — fertility for a farm, minerals for a mine, oil for a well. A mine on rock
with 5 minerals produces a twentieth of one on 100.

Nothing in this step moves anything between sectors.

### 6–7. Moving things
All movement for the update is **planned first, then resolved together**:
delivery orders, distribution, rail shipments, parcels held over from last
update, and hand moves.

Then contention is settled, because a source's stock, a sector's mobility and a
rail line's capacity can all be over-claimed:

1. **Proportionally to demand** — everyone claiming gets the same fraction.
2. Ties go to the **lower commodity priority** (food and petrol first).
3. Remaining ties are broken by **seeded randomness**.

Nobody wins because they asked first. There is no first.

### 8. Money
Taxes come in from civilians and workers; military pay, maintenance and
construction costs go out. If the treasury falls below the bankruptcy
threshold, you are marked bankrupt — and the **effects apply next update**, not
this one.

### 9. Levels
Tech and research are **stocks**: what you produce is added, then everything
**ages by about 1% per 96 ETUs**. Education and happiness are **moving
averages** of a rate — stop producing them and they drift back down.

A country below a fifth of the leader's tech has a 20% chance each update of
closing a third of the gap. Being far behind is not permanent.

### 10. Detection
Radar, ships and planes roll against a probability shaped by range, elevation,
tech, terrain and how visible the target is. A hit becomes a **contact** with a
confidence and an age. Contacts go stale and are eventually dropped. Sectors
adjacent to your own are always visible.

Detection is probabilistic, not a range check: something inside your radar
range may go unseen, and the same thing may be seen one update and not the
next.

### 11. News, and 12. Apply
Events are collected, then the ledger is applied to produce the new world. Two
invariants are checked, and a failure rolls the whole update back and pauses
the game rather than saving a corrupt world:

- **Conservation**: every commodity's new total equals the old total plus what
  was produced, minus what was consumed and destroyed. Nothing appears or
  vanishes unaccounted for.
- **Determinism**: the same world, config and seed always produce the same
  result.

## Where this deliberately differs from the original

If you played Empire in the eighties, these will feel wrong, and they are
intentional. Each is recorded in `config/schema.yaml`.

- **No scan order.** The original processed sectors in a fixed order, and
  players exploited it. Here the ledger makes order unobservable — which is why
  standing-order chains move one hop per update instead of cascading.
- **Reach limits on distribution.** The original had none; mobility alone was
  the limit. Here a shipment also has a maximum number of sectors per update.
- **Tech scales every production rate.** The original gated only some products
  on tech.
- **Probabilistic detection.** The original's radar was a binary range check.
- **Subsistence.** People below the per-sector limit forage and cannot starve.
  The original was harsher.
- **Closed-form population growth** rather than compounding per ETU. Equivalent
  for growth; it does not interleave eating and breeding the way a per-ETU loop
  would.
- **Rail acts as a cheap road**, discounting movement into a sector even with
  no train involved.

Numbers marked GUESS in the schema are placeholders calibrated against nothing
yet. If a rate feels wrong, it may simply be wrong — that file says which ones
are load-bearing and which are invented.
