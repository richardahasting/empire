# Ships

Ships need a **harbour** at 60% efficiency or better. Everything else follows
from that.

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

`lane SHIP from to [COMMODITY ...]` sets a shuttle: load surplus at one end,
unload at the other, turn round, repeat, every update without further orders.
With no commodities named it keeps the far end's thresholds topped up.

`sail SHIP x,y` sends a ship somewhere once. `load` and `unload` work **only in
a harbour**.

## Being seen

A ship lifts the fog around it: everything within its sight radius is visible
to you while it is there. That makes even a fishing boat a scout.

It works both ways — your ships are what other people's radar and lookouts
detect. Detection is probabilistic, so a ship may slip past a radar one update
and be spotted by it the next. Held cargo has a low signature of its own, so
being seen does not necessarily mean the cargo was.
