# Ships

Ships need a **harbour** at 60% efficiency or better. Everything else follows
from that.

**Standing missions** — given once, run every update until told `off`:
`fish` (fishing boats), `mine` (deep sea miners), and `lane` and `supply`
(anything with a hold: cargo ships, tankers, luxury craft). `sail` is the one-off order. The
`ships` listing shows what each hull is **on** and what its class **can** be
given, so nobody has to steer a miner by hand.

## Building one

`build HARBOUR CLASS [name]` lays down a hull. It costs materials and cash from
that harbour's own stock, and the class must be within your tech.

A new hull leaves the yard at **20% efficiency** and fits out while docked,
gaining 20 points an update at 1 lcm and $2 a point. A ship's speed is
`class speed × efficiency`, so a half-finished ship is a slow ship.

**A hull keeps the tech level it was laid down at.** Later research does not
improve ships already built.

| class | tech | hold | speed | what it is for |
|---|---|---|---|---|
| fishing_boat | 0 | 300 | 3 | food from the sea |
| cargo_ship | 0 | 600 | 3 | anything |
| tanker | 15 | 1500 | 3 | oil and petrol only |
| industrial_fishing_boat | 20 | 1200 | 4 | three times the catch |
| super_cargo | 25 | 2400 | 4 | anything, in bulk |
| luxury_craft | 30 | 100 | 3 | happiness, at $50 an update |
| super_tanker | 35 | 6000 | 4 | oil and petrol in bulk |
| destroyer, frigate | 40, 45 | — | 5 | warships |
| submarine | 50 | — | 4 | hard to see |
| battleship, carrier | 60, 70 | — | 4 | warships |

## The sea wears a ship out

The sea is a demanding place, and everything floating in it is falling apart
slowly. **A hull at sea loses 3% efficiency every update.** A hull in one of your
own harbours loses none — it is being looked after there.

That matters more than it sounds, because efficiency drives almost everything a
ship is: its speed is `class speed × efficiency`, and so is how much movement it
can bank. A tired ship is a slow ship before it is anything else.

**At 60% a ship on a standing mission breaks off and goes home.** It stops
working, makes for its home harbour, and **will not go out again until it is back
at 100%**. A harbour restores 20 points an update and charges materials and cash
for each, so a badly worn hull is in dock for several updates and a worn fleet is
a real bill.

It keeps its orders throughout. Once it is fully refitted it goes back to what it
was doing, unbidden — you do not have to send it out again.

A ship you are sailing by hand is not managed for you: it will wear down to
nothing if you let it.

## Fuel

**Every hull burns petrol by the hex**, including fishing boats and freighters.
A harbour refuels from its own stock; a tanker can refuel others at sea.

A dry tank holds a ship exactly where it is. Tanks are sized at roughly thirty
hexes, so seven to ten updates of steady sailing.

This is what finally gives the refinery a customer. Before ships, `1 oil → 10
petrol` produced a commodity nothing consumed.

## Crews

A hull needs people aboard to sail. Merchantmen — fishing, cargo, tanker,
luxury — take **civilians**; everything else takes **military**. The harbour
signs them on while the ship is docked, from the people who are actually there,
and they come ashore when it is scrapped.

**A short-handed ship stays at the quay.** This is deliberate: a fleet competes
with your factories for the same population. Ships are not free once built.

## Fishing

`fish SHIP` sends a boat out on a standing mission: roam the waters near its
home harbour, fish, return when the hold is 90% full, unload, repeat. It picks
richer water more often than poor water.

The sea is fertile **by region**, not by hex — fishing grounds come in patches
of about 4×4, so finding good water is worth something and staying in it is
worth more.

Catch is `rate × the hex's fertility × ETUs × efficiency`, so a fully fitted
boat in rich water lands several hundred food an update.

## Sailing

`sail SHIP x,y` moves the ship **now**, not at the update. It goes as far as its
own mobility and its tank will carry it, this command, and whatever is left of
the journey happens at the update.

Every hull has a **mobility pool** of its own, as a sector does. It fills by the
ship's speed each update — `class speed × efficiency`, adjusted for the tech it
was laid at — and is capped at two updates' worth. So a ship that has been
sitting in harbour can dash, and one that has been working all update cannot.

**Haste costs more.** A hex ordered now spends **1.25 mobility**; a hex sailed
as part of a plan — a standing mission, or the remainder of an order left to the
update — spends 1. Rushing is always available and never free, and a captain who
plans gets further on the same movement.

A ship that is short-handed, out of fuel or out of mobility does not refuse the
order: it takes the destination and starts at the update.

## Mining the sea floor

`mine SHIP` sends a deep sea miner out on a standing mission, exactly as `fish`
does: roam the water near its home harbour, work whatever it is over, come home
when the hold is full, land the load, repeat.

What it brings up is **iron**, from **polymetallic nodules** — the lumps of
manganese, nickel, copper and cobalt that lie loose on the sea floor. Ore comes
up at half the rate fish come up, and the ship burns petrol to earn it, which is
the trade.

Nodules are **rarer than fish and come in fields**. Fishing grounds vary by
region and there is some fertility almost everywhere; nodules are drawn over
larger regions and about two thirds of the sea has none at all. Finding a field
is worth something, and a miner sitting over barren water brings up nothing —
its history will say so.

They do not run out. Nodules form over millions of years, so nothing here
depletes them.

A deep sea miner needs tech 35, and costs rather more than a trawler.

## Cargo runs

`lane SHIP from to [COMMODITY ...]` sets a shuttle between two of your harbours:
load at one end, unload at the other, turn round, repeat, every update without
further orders.

- **With no commodities named, the far end's thresholds decide.** The ship loads
  only what the second harbour is short of — less anything already on its way
  there in your other ships — and **waits at the loading end** when it wants
  nothing, instead of burning petrol on an empty round trip. This is the same
  rule as a rail lane: the destination says what it wants with `thresh`, and the
  lane fills it.
- **With commodities named**, it pushes those: everything above the loading
  harbour's own thresholds, up to the hold.

A ship does the harbour's business **the update it arrives**: it unloads (or
loads) and turns round straight away, rather than sitting at the quay for an
update first.

`sail SHIP x,y` sends a ship somewhere once. `load` and `unload` work **only in
a harbour**.

## Supply: islands that feed themselves

`supply SHIP [home]` puts a ship on the supply round. You never order a
shipment. Instead:

1. Set **thresholds** on the harbours that need things — `thresh 12,-4 lcm 400`
   on the island harbour, say.
2. Put one or more ships on `supply`.

Every update, each supply ship looks at all your harbours. In a harbour it first
**lands what that harbour is short of** — only that much, so the rest can go on
— then **takes on what other harbours are short of** and this one can spare.
Then it sails for whichever shortage is most pressing: to the harbour that
wants what it carries, or, empty, to the harbour that can fill it.

- **Most nearly empty goes first.** A harbour at a tenth of its threshold is
  served before one at eight tenths, however big the numbers; after that, the
  shorter trip.
- **A harbour keeps what its own thresholds say.** Everything above them is
  spare. A harbour with no threshold for something will give all of it away.
- **Ships do not pile onto one shortage.** Cargo already bound for a harbour in
  any of your ships counts against what it is short of.
- The harbour's **warehouse next door** counts with it, for both what it wants
  and what it can spare.
- **Nothing to do** keeps a supply ship waiting in harbour, or sends it home from
  sea. It carries on by itself as soon as a threshold goes short.
- **It only carries what its class carries.** A tanker on supply moves oil and
  petrol and nothing else, which makes it the refinery's supply line: set an
  `oil` threshold on the refinery's harbour and a `pet` threshold wherever your
  ships refuel.
- `home` is where it goes to **refit** when the sea wears it to 60%, exactly as a
  fishing boat does. Give the order in that harbour, or name it.

`supply SHIP off` takes it off the round. A `sail` order also ends it.

## Tankers

Tankers carry only oil and petrol, but a lot of it, and they do three jobs:

- **Bulk carriers** for a lane or the supply round.
- **Refuelling at sea**: a ship with a dry tank in the same hex as your tanker
  is filled from the tanker's hold.
- **They never run dry with petrol aboard.** A tanker that cannot make another
  hex fills its own tank from its hold. Only then, so most of what it was
  carrying for someone else still arrives.

## The logbook

`history SHIP [N]` shows what a ship did over its last N updates (5 unless you
say otherwise), a numbered line for each thing: fitted out, loaded, bound for,
sailed, arrived, unloaded. The Fleet panel has the same thing under **History**.
The `ships` listing still shows the last update run together on one line.

## Being seen

A ship lifts the fog around it: everything within its sight radius is visible
to you while it is there. That makes even a fishing boat a scout.

It works both ways — your ships are what other people's radar and lookouts
detect. Detection is probabilistic, so a ship may slip past a radar one update
and be spotted by it the next. Held cargo has a low signature of its own, so
being seen does not necessarily mean the cargo was.
