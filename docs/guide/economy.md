# The economy

A sector turns **people** into **goods**. Everything else is detail.

## Designations

A sector does nothing until you give it a job with `des`. The job decides what
it makes, what it needs to make it, and which of the sector's natural resources
limits it.

The chains are short and they interlock:

```
fertility  --agribusiness-->  food
minerals   --mine----------->  iron ore
iron       --light_manufacturing--> lcm        (1 iron -> 1 lcm)
iron       --heavy_industry------->  hcm        (2 iron -> 1 hcm)
oil        --oil_field------>  oil
oil        --refinery------->  petrol          (1 oil -> 10 petrol)
gold       --gold_mine------>  gold dust
dust       --bank----------->  gold bars       (5 dust -> 1 bar)
lcm+hcm    --shell_plant---->  shells          (2 lcm + 1 hcm -> 1 shell)
oil+lcm+hcm--gun_plant------>  guns            (1 oil + 5 lcm + 10 hcm -> 1 gun)
uranium    --uranium_mine--->  radioactive material
fertility  --forest--------->  a little lcm, and happiness   (forest terrain only)
civilians  --enlistment_center--> military
```

**Light construction materials (lcm) are the spine of the economy.** Almost
everything is built from them, and they come from iron, which comes from a mine
on a sector with minerals. If you build nothing else early, build that chain.

There are also sectors that make no goods: `capital`, `warehouse`, `harbor`,
`depot`, `city`, `school`, `university`, `research_lab`, `technical_center`,
`bank`, `fortress`, `radar`, `park`, `hospital`.

A `radar` sector sees as far as its efficiency lets it: up to 12 hexes at 100%,
more with tech, never more than 30. Everything in reach shows on your map, and
enemy ships in it can be detected. Select it to see the ring.

## The four limits on production

A sector produces the **smallest** of these. Knowing which one is binding is
most of playing well. Click any sector you own on the map: the inspector's
**Makes** panel works the four out for that hex and names the one that binds.

**1. Efficiency.** Below 60% a sector produces *nothing at all*. Above it,
output scales linearly: an 80% mine makes 80% of what a 100% mine would.

**2. Work.** Work is people: `civ × 1.0 + uw × 1.0 + mil × 0.5`, times the
ETUs in the update, adjusted by happiness. Construction takes its half first;
production gets the remainder. A perfectly designated sector with nobody
standing on it produces nothing.

**3. The resource gate.** Every extraction sector is scaled by that hex's own
endowment, 0–100. A mine on 90 minerals produces eighteen times what the same
mine on 5 minerals produces. **This is decided when the world is generated and
never changes** — you cannot improve a sector's minerals, only find a better
sector. Farms are gated on fertility, mines on minerals, gold mines on gold,
wells on oil, uranium mines on uranium.

Manufacturing sectors have no resource gate: a factory works as well anywhere.

The sea has two of its own. **Fertility** feeds fishing boats, and **minerals** —
nodule fields — feed deep sea miners, which land iron. Both are drawn by region
rather than by hex, so good water comes in patches. See **[Ships](ships.html)**.

**4. Inputs.** A refinery with no oil makes no petrol. Inputs are consumed in
proportion to what is actually made.

## Storage

A sector holds **10,000** of each commodity. A warehouse or a city holds ten
times that. A warehouse does **not** store more per commodity than the ten
times — what it really does is make goods *leave* it at a tenth of their
shipping weight, which matters enormously for distribution.

Anything produced beyond the ceiling is lost.

## Efficiency, precisely

Building costs materials, cash and work. Most designations cost **$1 per
efficiency point and no materials at all** — a fortress is the exception at 1
hcm and $5 a point.

Half a sector's work may go to construction, at **one point per work unit**. So
the limit is people:

| civilians in the sector | efficiency points per update |
|---|---|
| 100 | 30 |
| 500 | 100 (from 0 to 100 in one update) |
| 1,000 | 100 (capped by the sector, not the work) |

**Redesignating resets efficiency to zero** by default. Changing a sector's job
throws away everything you invested in it. The one exception is leaving
sanctuary, which keeps its 100%.

## BTUs

Every command costs Bureaucratic Time Units. You hold at most **640**, and
refill `0.0012 × capital civilians (up to 1,000) × ETUs × capital efficiency%`
per update — 640 for a healthy capital.

Most commands cost 1. Exploring, laying rail, rail shipments and laying down a
ship cost 2.

Two things follow. **Your capital is your action budget**: let it decay or lose
its people and you can do less each update, everywhere. And **BTUs are never
part of your score** — there is no advantage to hoarding them.

## Money

Income comes from two places, both as the original paid them:

- **Tax** on civilians and workers, **scaled by their sector's efficiency**. At 60
  ETUs a civilian in a 100% sector pays $0.50 an update; a sector at 40% pays 40% of
  that, so redesignating a crowded sector costs tax while it builds back up.
- **Interest on gold bars, in a bank only** (if the game has interest on). Each bar
  pays $0.25 an ETU, scaled by the bank's efficiency: $15 an update at 60 ETUs. A
  bank holds 10,000 bars. Bars in a warehouse or the capital earn nothing, so set
  each bank's `bar` threshold to 10000 and let distribution bring them in.

Spending is military pay, infrastructure maintenance, and the cash cost of
construction and production. **Military pay is the big one**: a soldier costs $5
an update whatever his sector's efficiency, ten times what a civilian pays in tax.
An enlistment centre only ever adds soldiers, so an army nobody is watching grows
its payroll every update. `demob SECTOR keep N` stands the surplus down (they
become civilians where there is room); see the [command reference](commands.html).

If the treasury falls below the bankruptcy threshold you are marked bankrupt,
and **the penalty lands the following update** — you get one update's warning.

## Happiness and the work you get

Happiness scales every sector's work pool: **+1% work per point**, with no
ceiling (Richard 2026-09-15: "a happy workforce is a productive workforce"). It
counts wherever work is spent: production, efficiency build-up and paving. A
country at happiness 50 does half as much again as one at 0 with the same
people. Happiness is produced by parks and forests and is a moving average — it decays if
you stop.

**A forest doubles as a park** (Richard 2026-09-15). On forest terrain it makes a
little lcm and, from the same people's leisure, half a park's happiness per
person, at **$0.25 a point** against a park's $1; its lcm costs nothing. It holds
500 people to a park's 1,000. Like its lcm, its happiness scales with the ground's
fertility and your tech, so a forest on rich ground does best.
