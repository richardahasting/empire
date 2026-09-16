# Command reference

Everything you can type into the console. Most commands cost **1 BTU**;
exploring, laying rail, rail shipments and laying down a ship cost **2**.

## Quick reference

This block is the single source of truth for command syntax: the in-game
`help` command prints exactly this text, read from this file. If the two ever
disagree, something is broken.

```text
map                          your map (relative coordinates, capital at 0,0)
census                       one line per owned sector: stocks (pet, gun and shell too), days of food left, standing deliveries, stalled roads
census res                   the ground: fert / min / gold / oil / uran per sector, and what it is poor for
food                         where the food is and is not: basins, deficits, and the deliver that would help
break                        break sanctuary
des SECTOR TYPE              designate (agribusiness, mine, light_manufacturing, warehouse, ...); the ack says
                             "now TYPE (glyph)", any efficiency change, then what auto-wiring set as a note;
                             a glyph or an alias works too (des 1,2 j · des 1,2 manufacturer); a farm or mine on
                             poor ground (resource below 30) is allowed but WARNS
thresh SECTOR COMMODITY N    set a distribution threshold (negative clears)
dist SECTOR cx,cy | none     name a sector's distribution centre
demob SECTOR N               stand N military down now (demob SECTOR all · demob SECTOR keep N leaves N); they become
                             civilians while the sector has room and the rest go home. Soldiers draw pay every update
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
unrest                       sectors that are disloyal, not all at work, occupied, or fighting guerrillas
anti SECTOR                  send the garrison after the guerrillas there; lose every soldier and the partisans take it
history SHIP [N]             a ship's logbook: what it did, a line at a time, for its last N updates (5)
manifest [SHIP]              what each ship has caught, mined, cruised and delivered since she was built (one ship in full)
contacts                     other people's ships your radar and lookouts have seen, and how long ago
build HARBOUR CLASS [name]   lay a hull in your harbour (fishing_boat, cargo_ship, ferry, tanker, luxury_craft, ...; tech gates apply)
sail SHIP x,y | hold         sail to a sea hex or one of your harbours (speed × efficiency hexes per update);
                             a standing mission or lane is paused, and resumes when she arrives
load/unload SHIP COMMODITY N in your harbour only
lane SHIP x,y x2,y2 [COMMODITY ...]   shuttle between two harbours, repeat; lane SHIP none clears
                             with no commodities it carries only what x2,y2's thresholds are short of
supply SHIP [x,y] | off      supply mission: fill any of your harbours short of a threshold from one that can
                             spare it, carrying only what the class carries; x,y is home, where it refits
fish SHIP [x,y] | off        fishing mission: roam the grounds near the home harbour, fish, land the catch, repeat
mine SHIP [x,y] | off        seabed mining: roam the nodule fields near home, mine, land the ore, repeat
army                         your land units: where, efficiency, soldiers, food, mobility, attack and defence
build HQ CLASS               raise a land unit (cavalry, infantry, artillery, engineer, supply, security) in a headquarters
march UNIT x,y               march a unit through your own land on its own mobility
lload / lunload UNIT COMMODITY N   a unit takes on, or puts down, soldiers and supplies in its sector
build x,y PLANECLASS         lay down a plane on an airfield of yours
bomb PLANE x,y [strategic]   a bombing sortie; strategic wrecks the sector, pinpoint what is in it
recon PLANE x,y              a reconnaissance sortie: it puts the sector on your chart
air / planes                 your planes, where they sit and how far they strike
work UNIT [mobility]         an engineer builds the sector it stands in with its own mobility
ufire UNIT x,y               artillery shells an enemy sector within its range
sabotage UNIT                a spy blows up the enemy sector it stands in, with one of its shells
incite UNIT                  a spy turns that sector's people against their owner
board UNIT SHIP              a light unit goes aboard a ship of yours in harbour; it travels with her
ashore UNIT                  it steps ashore where she lies (your own sector); on enemy coast, land SHIP x,y
attack x,y N from x2,y2 [N2 from x3,y3 ...] [unit U ...]   at war: N military from your sector x2,y2 (and more from others next to
                             x,y) attack the enemy sector x,y, man for man against it and its neighbours' military;
                             win and it is yours; lose and all sent are gone. Sending costs a move's mobility
land SHIP x,y                assault ship: put everyone aboard (up to 100 mil, 20 civ) ashore on the unowned land
                             sector next to her; it becomes yours. At war, on enemy coast: an assault, man for
                             man against the sector's and its neighbours' military; win and it is yours
fire SHIP x,y [CLASS]        fire now on a ship you can see at x,y (a fresh contact, or in her sight); the
                             target and everything of hers in reach answer at once. At peace it declares
                             nothing but marks your ship: they may shoot her on sight for 3 updates
patrol SHIP x,y x2,y2 ... | off   warship: walk the points in order, and round again
search SHIP | off            warship: wander the water near home, where you have not looked lately
escort SHIP OTHER | off      warship: stay with one of your other ships
blockade SHIP x,y | off      warship: hold x,y; at war, a hostile ship next to her is stopped there
interdict SHIP x,y | off     warship: hold x,y; at war, shell enemy trains within her guns' reach
                             every mission comes home for shells, fuel, crew and repairs, then goes back out
                             tenders have no order: one with no other orders answers your stranded ships' distress calls
scrap SHIP                   in harbour; the hold goes ashore
move COMMODITY x,y x2,y2 N   move now; the sending sector pays the route's mobility now
expl x,y x2,y2 N             explore into an adjacent unowned sector with N civilians
road SECTOR LEVEL            standing order: pave toward LEVEL (0 cancels); nothing is laid until a whole point's
                             materials are in the sector — the ack says the cost here and what is still needed
rail SECTOR LEVEL            standing order: lay rail toward LEVEL (needs tech 60; 0 cancels); same rule, and a
                             tunnel or bridge pays its one-time materials with the first points
SECTOR is x,y · * (all yours) · *:TYPE (all of one designation, id or glyph, e.g. *:a) · x1:x2,y1:y2 (a rectangle)
  on the web map, shift + drag selects a rectangle for the same orders
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

**`demob SECTOR N`** stands N military down, now. Every soldier draws pay each
update (ten times what a civilian pays in tax), and nothing else shrinks an army:
an enlistment centre only adds to it. The discharged become civilians while the
sector has room under its population cap; the rest go home and are gone. `demob
SECTOR all` stands them all down, and `demob SECTOR keep N` leaves N. Like `des`,
SECTOR can be many sectors (`demob * keep 50`). In the web client it is on the sector
menu, and on a dragged selection.

**`attack x,y N from x2,y2`** takes an enemy sector over land, at war. Name as many
of your sectors next to it as you like (`attack 5,2 80 from 4,2 40 from 4,3`); their
soldiers leave home and fight as one body, man for man, against the sector's
garrison and its owner's military in the sectors around it. A fortress's defenders
count double, and a soldier from a run-down sector is worth less. Win and the sector
is yours with its people, garrisoned by your survivors, less a tenth of its goods and
some of its road and rail, and its mobility falls to 0. Lose and everyone you sent is
gone. Moving soldiers in costs mobility as a move would: a sector pays each soldier's
cost of entering the target (so its mobility caps how many it can send), and its dead
cost it up to 20 more. In the web client it is **Attack…** on an enemy sector's
menu. An assault ship does the same from the sea (see [Ships](ships.html)).

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
