# Command reference

Everything you can type into the console. Most commands cost **1 BTU**;
exploring, laying rail, rail shipments and laying down a ship cost **2**.

## Quick reference

This block is the single source of truth for command syntax: the in-game
`help` command prints exactly this text, read from this file. If the two ever
disagree, something is broken.

```text
map                          your map (relative coordinates, capital at 0,0)
census                       one line per owned sector: stocks, days of food left, standing deliveries, stalled roads
census res                   the ground: fert / min / gold / oil / uran per sector, and what it is poor for
food                         where the food is and is not: basins, deficits, and the deliver that would help
break                        break sanctuary
des SECTOR TYPE              designate (agribusiness, mine, light_manufacturing, warehouse, ...); the ack says
                             "now TYPE (glyph)", any efficiency change, then what auto-wiring set as a note;
                             a glyph or an alias works too (des 1,2 j · des 1,2 manufacturer); a farm or mine on
                             poor ground (resource below 30) is allowed but WARNS
thresh SECTOR COMMODITY N    set a distribution threshold (negative clears)
dist SECTOR cx,cy | none     name a sector's distribution centre
deliver COMMODITY SECTOR DIR N   standing order: above N, push it one hex DIR (e ne nw w sw se) every update; DIR none clears
deliver COMMODITY SECTOR DIR N check   the same, described but not made: what lies that way, and whether it would deliver
  food out of a sector that keeps less than its own people eat in an update WARNS (a self-starving pipe)
adjacent SECTOR              what lies each way — yours, unowned, sea, or somebody's — without claiming or ordering anything
declare war COUNTRY          go to war; they are at war with you whether they like it or not
peace COUNTRY                offer peace, or accept theirs — it takes both of you
telegram COUNTRY "..."       a private message to one country; they see who it is from
announce "..."               the same, to everybody in the game
macro                        list your macros (recorded from the map); macro run N SECTOR runs one
ships                        your fleet: where each ship is, its load, where it is going, what it did
history SHIP [N]             a ship's logbook: what it did, a line at a time, for its last N updates (5)
contacts                     other people's ships your radar and lookouts have seen, and how long ago
build HARBOUR CLASS [name]   lay a hull in your harbour (fishing_boat, cargo_ship, tanker, luxury_craft, ...; tech gates apply)
sail SHIP x,y | hold         sail to a sea hex or one of your harbours (speed × efficiency hexes per update)
load/unload SHIP COMMODITY N in your harbour only
lane SHIP x,y x2,y2 [COMMODITY ...]   shuttle between two harbours, repeat; lane SHIP none clears
                             with no commodities it carries only what x2,y2's thresholds are short of
supply SHIP [x,y] | off      supply mission: fill any of your harbours short of a threshold from one that can
                             spare it, carrying only what the class carries; x,y is home, where it refits
fish SHIP [x,y] | off        fishing mission: roam the grounds near the home harbour, fish, land the catch, repeat
mine SHIP [x,y] | off        seabed mining: roam the nodule fields near home, mine, land the ore, repeat
fire SHIP x,y [CLASS]        fire now on a ship you can see at x,y (a fresh contact, or in her sight); the
                             target and everything of hers in reach answer at once. At peace it declares
                             nothing but marks your ship: they may shoot her on sight for 3 updates
patrol SHIP x,y x2,y2 ... | off   warship: walk the points in order, and round again
search SHIP | off            warship: wander the water near home, where you have not looked lately
escort SHIP OTHER | off      warship: stay with one of your other ships
blockade SHIP x,y | off      warship: hold x,y; at war, a hostile ship next to her is stopped there
interdict SHIP x,y | off     warship: hold x,y; at war, shell enemy trains within her guns' reach
                             every mission comes home for shells, fuel, crew and repairs, then goes back out
scrap SHIP                   in harbour; the hold goes ashore
move COMMODITY x,y x2,y2 N   move now; the sending sector pays the route's mobility now
expl x,y x2,y2 N             explore into an adjacent unowned sector with N civilians
road SECTOR LEVEL            standing order: pave toward LEVEL (0 cancels); nothing is laid until a whole point's
                             materials are in the sector — the ack says the cost here and what is still needed
rail SECTOR LEVEL            standing order: lay rail toward LEVEL (needs tech 60; 0 cancels); same rule, and a
                             tunnel or bridge pays its one-time materials with the first points
SECTOR is x,y · * (all yours) · *:TYPE (all of one designation, id or glyph, e.g. *:a) · x1:x2,y1:y2 (a rectangle)
  each sector pays its own BTU; e.g. road * 100 · thresh *:agribusiness hcm 50 · thresh -2:2,-2:2 food 100
  thresh on * or a rectangle scales goods by designation (warehouse ×10, people ×1); *:warehouse or one sector sets it as typed
  the ack says what each kind of sector actually got, e.g. lcm 400 in 43 sectors, 4000 in 2 warehouse sectors
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
