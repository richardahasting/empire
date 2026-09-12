# Command reference

Everything you can type into the console. Most commands cost **1 BTU**;
exploring, laying rail, rail shipments and laying down a ship cost **2**.

## Quick reference

This block is the single source of truth for command syntax: the in-game
`help` command prints exactly this text, read from this file. If the two ever
disagree, something is broken.

```text
map                          your map (relative coordinates, capital at 0,0)
census                       one line per owned sector
break                        break sanctuary
des SECTOR TYPE              designate (agribusiness, mine, light_manufacturing, warehouse, ...)
thresh SECTOR COMMODITY N    set a distribution threshold (negative clears)
dist SECTOR cx,cy | none     name a sector's distribution centre
deliver COMMODITY SECTOR DIR N   standing order: above N, push it one hex DIR (e ne nw w sw se) every update; DIR none clears
telegram COUNTRY "..."       a private message to one country; they see who it is from
announce "..."               the same, to everybody in the game
macro                        list your macros (recorded from the map); macro run N SECTOR runs one
ships                        your fleet: where each ship is, its load, where it is going, what it did
contacts                     other people's ships your radar and lookouts have seen, and how long ago
build HARBOUR CLASS [name]   lay a hull in your harbour (fishing_boat, cargo_ship, tanker, luxury_craft, ...; tech gates apply)
sail SHIP x,y | hold         sail to a sea hex or one of your harbours (speed × efficiency hexes per update)
load/unload SHIP COMMODITY N in your harbour only
lane SHIP x,y x2,y2 [COMMODITY ...]   shuttle: load surplus at x,y, unload at x2,y2, repeat; lane SHIP none clears
fish SHIP [x,y] | off        fishing mission: roam the grounds near the home harbour, fish, land the catch, repeat
mine SHIP [x,y] | off        seabed mining: roam the nodule fields near home, mine, land the ore, repeat
scrap SHIP                   in harbour; the hold goes ashore
move COMMODITY x,y x2,y2 N   move now; the sending sector pays the route's mobility now
expl x,y x2,y2 N             explore into an adjacent unowned sector with N civilians
road SECTOR LEVEL            standing order: pave toward LEVEL (0 cancels)
rail SECTOR LEVEL            standing order: lay rail toward LEVEL (needs tech 60; 0 cancels)
SECTOR is x,y · * (all yours) · *:TYPE (all of one designation, id or glyph, e.g. *:a) · x1:x2,y1:y2 (a rectangle)
  each sector pays its own BTU; e.g. road * 100 · thresh *:agribusiness hcm 50 · thresh -2:2,-2:2 food 100
  thresh on * or a rectangle scales goods by designation (warehouse ×10, people ×1); *:warehouse or one sector sets it as typed
railship COMMODITY x,y x2,y2 N   train N units between two depots at the update (line checked now)
raillane x,y x2,y2 [COMM ...]    standing run between two depots, every update; no list = keep the far end's thresholds topped up
raillane x,y x2,y2 none          cancel that lane
```

## Naming sectors

Coordinates are **relative to your capital**, which is always `0,0`. Your
neighbour's coordinates for the same hex are different from yours.

Anywhere a command takes `SECTOR`, it also takes a set of them:

| form | means |
|---|---|
| `2,-1` | one sector |
| `*` | every sector you own |
| `*:TYPE` | every sector of one designation — `*:mine`, or by glyph, `*:a` |
| `x1:x2,y1:y2` | a rectangle, e.g. `-2:2,-2:2` |

**Each sector in the set pays its own BTU.** `road * 100` on forty sectors
costs forty BTUs, not one. This is the fastest way to empty your budget.

A threshold set on `*` or a rectangle **scales by designation**: a warehouse
gets ten times the quantity, people are unscaled. Set it on a single sector or
on `*:warehouse` and the number is taken literally. This catches people out —
`thresh * food 100` does not put 100 food everywhere.

## The ones worth explaining

**`break`** leaves sanctuary. Irreversible, and until you do it nothing outside
your two starting sectors is yours to touch. Your sectors keep their 100%
efficiency when you break.

**`des SECTOR TYPE`** gives a sector a job. **Redesignating resets efficiency
to zero** — you lose everything invested in the old job. Designating also wires
the sector into distribution automatically, pointing it at the nearest hub it
can reach and giving it thresholds for its type, but it never overwrites a
choice you made yourself.

**`thresh SECTOR COMMODITY N`** — below `N` the sector asks its distribution
centre for more; above `N` it offers the surplus. A negative number clears it.

**`dist SECTOR cx,cy`** names the distribution centre a sector talks to.
`dist SECTOR none` unhooks it.

**`deliver COMMODITY SECTOR DIR N`** — a standing order: each update, push
anything above `N` **one hex** in direction `DIR` (`e`, `ne`, `nw`, `w`, `sw`,
`se`). `DIR none` clears it. One hop per update, always — chains advance a
single sector each update.

**`move COMMODITY from to N`** moves goods now, at **full mobility price**. The
sending sector pays for the whole route.

**`expl from to N`** pushes `N` civilians into an adjacent unowned sector to
claim it. Costs 2 BTU. This is how territory grows.

**`macro`** lists macros recorded from the map; `macro run N SECTOR` replays
one. Useful for repeating a designation-and-threshold setup across many
sectors.

## Ships

`build`, `sail`, `load`, `unload`, `lane`, `fish` and `scrap` are covered in
**[Ships](ships.html)**. Two rules that are easy to miss: `load` and `unload`
work **only in a harbour**, and every hull burns petrol by the hex.

## Rail

`rail` needs tech 60. `railship` moves a batch between depots at the next
update; `raillane` is the standing version. Rail also discounts ordinary
movement into a sector, with no train involved at all.
