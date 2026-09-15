# M5, the rest: land warfare and planes (#71). Plan for review

Drafted 2026-09-15 while Richard was away, for him to cut up. Nothing here is built. It follows the pattern the
ships took: slices that each play on their own, every number a GUESS in `config/schema.yaml`, and the original
(gefla/empserver `land.config`, `plane.config`, `commands/attk.c`, `lnd*.c`, `pln*.c`) as the reference.

## Where we stand

- **Sea:** ships, fuel, crews, missions, gunnery, blockades, tenders.
- **From the sea:** assault ships landing on unowned coast (#193) and assaulting held coast (#206).
  `command.Assault` already resolves a fight man for man, counting the defender's neighbours.
- **Not built:**
  - **No land attack.** A player cannot take an enemy sector next to their own. In the original this is the
    most basic act of war: `attack` with military from adjacent sectors, no units needed.
  - **No land units** of any kind.
  - **No planes.** `airfield` exists as a designation with the flag `builds_planes`, and nothing reads it.

## Proposed order

### Slice 1: attack with military (no units)
The original's `attack`: military from one or more of your sectors next to an enemy sector fight its garrison.
The winner takes the sector, and the attackers who survive move in.

- **Command:** `attack x,y N [from x,y ...]`, at war only (like assault). Mobility is spent by the sectors
  the soldiers leave (original: each attacking sector pays mobility).
- **Resolution:** reuse `Assault`'s man-for-man rolls, with fortress ×2 and the defender's adjacent military
  joining in. Only the attacker's side changes from a ship to sectors.
- **Capture:** the same outcome as a won assault (designation and people kept, goods lost, roads damaged,
  wiring cleared). Captured civilians pay a quarter tax until loyal (original `tax()`; we skipped that on
  2026-09-14). This is also where #72's disloyal conquered sectors would begin.
- **Web:** "Attack…" on the right-click menu of an enemy sector next to yours, choosing sectors and numbers.
- **Why first:** it is small, it reuses code that is tested, and it makes #72 (unrest) and every later unit
  meaningful.

### Slice 2: land units, the core four
A data table like ships, gated on tech, built in a sector (the original builds in a `headquarters`; ours
could use `fortress` or a new `barracks` designation).

| class | role | from the original |
|---|---|---|
| infantry | attack 1.0, defence 1.5 | `inf`, tech 50 |
| artillery | fires at sectors and units within range, weak in melee | `art`, tech 35, range 8 in tenths of a sector |
| engineer | builds and repairs, clears mines, strong defence | `eng`, tech 130 |
| supply | carries food, shells and petrol to units in reach | `sup`, tech 50 |

- **Carried:** each unit is a group of soldiers with efficiency, military aboard, food, shells and fuel. Its
  combat strength is military × attack or defence × efficiency, and it adds to a sector's defence or an
  attack.
- **Commands:** `build UNIT x,y`, `march UNIT x,y` (uses its own mobility, like a ship's pool), `attack` with
  units, `fire UNIT x,y` for artillery, `lload`/`lunload` onto ships (so marines and assault ships combine),
  `unit UNIT fortify` (hold and dig in).
- **Supply:** units eat food and draw shells and fuel from sectors or supply units within reach. An unsupplied
  unit loses efficiency. This makes the logistics game (roads, rail, depots) matter for war.
- **Later classes** (cavalry, armour, marines, AA, radar units, spies) are rows in the table once these four
  play.

### Slice 3: planes
Built at an `airfield` from lcm, hcm and military crews; they fly from airfields (and carriers) within range and
come back.

| class | missions | from the original |
|---|---|---|
| fighter | air defence, escort, intercept | `f2` P-51, tech 80 |
| bomber | bomb a sector (efficiency, goods, people), a ship or a unit | `mb` medium bomber, tech 80 |
| transport | carry goods or paratroops between airfields | `tr` Lodestar, tech 85 |
| recon | reveal sectors and units along a flight path | `re`, tech 130 |

- **Flying:** each flight is a path within `range`, fuel from the airfield's petrol, and flak from forts,
  AA units and ships along the way. A shot-down plane is lost.
- **Commands:** `build PLANE airfield`, `fly`, `bomb`, `drop`/`paradrop`, `recon`, and standing missions
  (`air defence`, `interdiction`) that fire during the update's combat step, as warships do.
- **Detection:** radar sees planes (the spec: "ship, plane and satellite detection are separate systems").
- **Carriers:** already have a hold of 800; they become mobile airfields here.

### Slice 4: agents and presentation
- **Agents:** teach the scripted fixture only the grammar (not strategy: agents learn through play).
- **Map:** unit and plane markers, flight paths, and the update's combat on the map.
- **Tests:** the guide chapters and the tests the ships got.

## Decisions for Richard

1. **Slice 1 first?** Taking land with military before units exist is the original's order and the smallest
   step. Or go straight to units.
2. **Where are land units built?** A new `barracks`, the original's `headquarters`, or `fortress`.
3. **How deep is supply?** The original's (units draw from sectors and supply units in range, and starve
   without) or simpler (units draw only from the sector they stand in).
4. **Planes before or after land units?** They are independent. Bombing makes sense only once there is
   something worth bombing, so after.
5. **Missiles and satellites** (the original's `ssm`…`icbm`, `landsat`, `spysat`, and nuclear warheads): in
   scope for M5, a later milestone, or never?
6. **Conquest and loyalty:** do captured sectors start disloyal (the original, and #72), and do captured
   civilians pay a quarter tax until they come round?
