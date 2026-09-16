# The army: land units

A **land unit** is a body of soldiers with its own supplies: it marches on its own mobility,
adds its strength to a fight, and hunts guerrillas. These are the original game's land units
(Richard 2026-09-15: the original is the default).

## Raising one

Designate a **headquarters** (`!`) and, once it is at 60%, `build HQ CLASS` (or right-click it,
**Build unit…**). A unit is raised at **10%** for a tenth of its materials and cost, and builds
up while it stands in the headquarters, from that sector's work and materials (a third as fast
anywhere else of yours, except a fortress).

| class | tech | attack | defence | speed | carries | for |
|---|---|---|---|---|---|---|
| cavalry | 30 | 1.2 | 0.5 | 32 | 20 mil, 12 food | fast, light |
| artillery | 35 | 0.1 | 0.4 | 18 | 25 mil, 40 shells, 10 guns, 24 food | its guns come with a later slice |
| infantry | 50 | 1.0 | 1.5 | 25 | 100 mil, 24 food | holding ground |
| supply | 50 | 0.1 | 0.2 | 25 | 25 mil and a store of shells, guns, petrol, food, lcm, hcm | carrying |
| engineer | 130 | 1.2 | 2.4 | 25 | 20 mil, 12 food | the best defenders; costs three times as much to keep |
| security | 170 | 1.0 | 2.0 | 25 | 50 mil, 30 food | hunting guerrillas |

A unit raised above its class's tech is a little stronger and faster.

## Soldiers and supplies

A unit is empty when it is raised. **`lload UNIT mil N`** takes soldiers from the sector it
stands in, up to what it carries; `lload UNIT food N` its rations; `lunload` puts them down.
A unit with no soldiers cannot fight. **`army`** (or the Army panel) lists your units.

## Every update

- Its soldiers are **paid** like any military, and the unit costs a little **upkeep**
  (0.1% of its cost an ETU; engineers three times that). A treasury that cannot pay costs it
  efficiency instead.
- Its men **eat from its own food**. Without rations they starve.
- It **builds up** toward 100% where it stands, and gains **mobility** (60 an update, at most 127).

## Marching and fighting

- **`march UNIT x,y`** goes through your own land the cheapest way, as far as its mobility
  carries it. Each hex costs the sector's move cost, adjusted for the unit's speed.
- **Defence:** units in a sector that is attacked, or next to it, fight with **soldiers ×
  defence × efficiency**. A hundred infantry at full strength are worth 150 of a sector's own.
- **Attack:** `attack x,y [N from x2,y2 ...] unit U` sends a unit from next door with **soldiers ×
  attack × efficiency**; it pays its march in, and if the sector falls it moves in with its
  survivors. The enemy sector's **Attack…** menu offers your units there.
- **Capture:** a sector's units fall with it. Each loses 29–128 points of efficiency; one
  left above 10% is captured, the rest are blown up by their crews.
- **Security troops** in a sector with guerrillas fighting you raid them every update and count
  three times over against them.

## Going to sea

A unit can ride a ship (issue #252, as the original's `ship.config` carried them):

- **`board UNIT SHIP`** (or **Board…** in the Army panel) puts a unit aboard a ship of
  yours lying **in one of your sectors**, where the unit stands. It keeps its soldiers,
  its supplies and its efficiency, and travels wherever she sails.
- **How many:** a ferry or a cargo ship takes **2**, a super cargo **4**, an assault ship
  **6**. Only **light** units go aboard — every class we have today is light.
- **`ashore UNIT`** (**Ashore**) steps it down where she lies, in a sector of yours.
- **While aboard** it cannot march, and cannot load or unload: it is at sea. Its food is
  eaten and its efficiency built up as usual.
- **In an assault:** `land SHIP x,y` against enemy coast sends the ship's own party **and
  every assault-trained unit aboard** (infantry, engineers). They fight as one body at the
  average worth of the men, and the survivors are shared out in proportion to what each
  sent. Win, and the units are ashore holding the sector; lose, and they are gone with the
  party. Units aboard without assault training (cavalry, artillery, supply, security) ride
  it out and stay aboard.
- **If she sinks**, whatever she carried goes down with her.
- She cannot be **scrapped** with a unit aboard.

## Engineers' works

An **engineer** standing in a sector of yours builds it with its own hands (issue #258,
the original's `work`): **`work UNIT [mobility]`**, or **Work** in the Army panel.

- **How much:** `mobility spent × its efficiency / 600` points of the sector's efficiency.
  A fresh engineer with 127 mobility puts on **21 points at once** — a quarter of a sector,
  where its own people might take several updates.
- **What it costs:** the sector type's ordinary build materials, out of **that sector's own
  stock**, and its cash out of the treasury. A city costs 1 lcm and 2 hcm a point; most
  types cost only a dollar. Short of materials it does what it can and says so.
- **Mobility** comes back the way a march's does: 60 a update, at most 127.
- Give it no number and it spends everything it has.

This is what makes an engineer worth its **three times the upkeep**: a forward harbour or
a new city can be finished the day it is designated instead of waiting on a workforce that
is not there yet.

## Artillery

A unit with guns shells a sector of theirs from where it stands (issue #256, the original's
`lnd_fire`): **`ufire UNIT x,y`**, or **Fire…** in the Army panel.

- **Artillery** carries ten guns, forty shells and a salvo of three; an **engineer** has one
  gun for close work. Load guns and shells into it with `lload` in a sector that has them.
- **It must be at 40% or better** (`LAND_MINFIREEFF`), ashore, with soldiers to work the guns.
- **Range** is `techfact(tech, range/2)` hexes — about three for artillery at the tech it is
  built at, further as your tech rises.
- **Damage** is `4 + roll(6)` a gun, by its efficiency: ten guns at 100% wreck something like
  half of a sector. Short of a full salvo it fires what it has, for proportionally less.
- **What it hits:** the sector's efficiency, roads, rail, mobility and every commodity in it.
  Shelling takes no ground — that is what `attack` is for; soften first, then attack.
- At war only.

## Spies

Two classes carry no army at all (issue #254): the **infiltrator** (tech 40) and the
**commando** (tech 55), which carries twenty shells. They are the original's `L_SPY`
units, and they break the rule every other unit obeys:

- **A spy marches into land that is not yours.** Anything else standing in another
  country's sector is seized, which is why `march` refuses it — `attack` is how the rest
  take ground.
- **It is worth nothing in a fight.** No defence for the sector it is in, and `attack`
  refuses to send it. It carries one or five soldiers to guide it, not to fight.
- **Being caught:** every sector of theirs it walks into, and every attempt it makes
  there, is a chance of `(110 − efficiency)/100` — one in ten at 100%, four in five at
  30%. **At war it is shot.** At peace it is only spotted, and they know it is there.

### `sabotage UNIT`

One shell, laid where it will hurt. The sector loses a percentage of **everything** —
efficiency, roads, rail, mobility and every commodity in it — of roughly 60 to 150 at
full efficiency, and **its own shells and petrol go up with it**: over 20 shells or over
100 petrol stored there add to the blast. The victim reads in the news that saboteurs
wrecked the place; it does not say whose they were. A second roll after the charge goes
off can kill the spy in his own explosion.

### `incite UNIT`

Richard's addition (2026-09-15), and the offensive use of unrest (#72). The spy works on
the sector's people: each attempt makes them **more disloyal to their owner**, and once
they are past the line where they stop working, some of them **take up arms** as
guerrillas fighting whoever holds the sector. From there #72 does the rest — guerrillas
fight the garrison, sabotage production, recruit, spread, and hand a sector back.

Rates for inciting are a GUESS in `config/schema.yaml`; everything else here is the
original's.

Artillery fire, engineers' works and planes come in later slices.
