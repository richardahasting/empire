# Ships

Ships need a **harbour** at 60% efficiency or better. Everything else follows
from that.

**Standing missions** — given once, run every update until told `off`:
`fish` (fishing boats), `mine` (deep sea miners), `lane` and `supply`
(merchantmen: cargo ships, tankers, luxury craft), and for warships `patrol`,
`search`, `escort`, `blockade` and `interdict`. `sail` is the one-off order. It
**pauses** a standing mission rather than ending it (see *Sailing*), and
`fire` is the one a warship gives now. The
`ships` listing shows what each hull is **on** and what its class **can** be
given, so nobody has to steer a miner by hand.

## Building one

`build HARBOUR CLASS [name]` lays down a hull. It costs cash, and materials from
**the quay**: the harbour's own stock and any warehouse beside it. The class must
be within your tech. A refusal for want of something names the nearest stock of
it, so you know where to send from. A hull that its quay has too few people to
crew is still laid down, and the reply warns you how many to send.

A new hull leaves the yard at **20% efficiency** and fits out while docked,
gaining 20 points an update at 1 lcm and $2 a point. A ship's speed is
`class speed × efficiency`, so a half-finished ship is a slow ship.

**A hull keeps the tech level it was laid down at.** Later research does not
improve ships already built.

| class | tech | hold | speed | what it is for |
|---|---|---|---|---|
| fishing_boat | 0 | 300 | 3 | food from the sea |
| cargo_ship | 0 | 600 | 3 | goods, not people |
| ferry | 10 | 300 | 4 | people: civilians, military, workers |
| tanker | 15 | 1500 | 3 | oil and petrol only |
| industrial_fishing_boat | 20 | 1200 | 4 | three times the catch |
| super_cargo | 25 | 2400 | 4 | goods in bulk, not people |
| tender | 20 | 600 | 2 | answers distress calls: petrol and repairs at sea |
| assault_ship | 30 | 100 mil + 20 civ | 4 | lands people on unowned coast |
| luxury_craft | 30 | 100 | 3 | happiness, at $50 an update |
| super_tanker | 35 | 6000 | 4 | oil and petrol in bulk |
| destroyer | 40 | — | 5 | 10 guns, range 3, armour 20; hunts submarines |
| frigate | 45 | — | 5 | 15 guns, range 3, armour 30; hunts submarines |
| submarine | 50 | — | 4 | 5 torpedoes that hit twice as hard, range 2; hard to see |
| battleship | 60 | — | 4 | 40 guns, range 5, armour 80 |
| carrier | 70 | — | 4 | 20 guns, range 3, armour 50 (planes come with #71) |

A warship's build cost includes guns and shells. **They go aboard as her
armament** — she leaves the yard ready to fight.

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

**The sea only wears a hull down so far, and then she limps home by herself.**
Wear stops at the efficiency she needs to make one hex an update: 34% for a
fishing boat or cargo ship, 25% for a speed-4 hull, 20% for a destroyer. A ship
at sea at or below that line **makes for the nearest harbour of yours on her own**,
whatever she was doing: sailed by hand, on a lane, or on any mission. However worn
she is, battle damage included, she makes **at least one hex an update** toward it,
as long as she has fuel and a crew. She keeps her orders: once she is in harbour
and refitted, a mission or lane carries on.

## Fuel

**Every hull burns petrol by the hex**, including fishing boats and freighters.
A harbour refuels from the quay (its own stock, then any warehouse beside it);
a tanker can refuel others at sea.

A dry tank holds a ship exactly where it is. Tanks are sized at roughly thirty
hexes, so seven to ten updates of steady sailing. At high tech a hull sails
further on each update and burns its tank faster.

Two rules stop a fleet from stranding itself (Richard 2026-09-14):

- **A ship does not leave port until her tank is full.** In harbour she refuels
  along with loading, unloading and fitting out, and stays in until she is topped
  off, whatever her orders, and whether you order a `sail` now or leave it to the
  update. A harbour with no petrol keeps her in, and her history says so. Send
  your harbours petrol: a refinery, a supply ship, a tanker on a lane.
- **A ship never sails further than she can get back from.** Every leg, from
  port or at sea, whatever her orders and wherever she is bound, stops where the
  fuel left will still take her to the nearest harbour of yours, with a quarter to
  spare. A ship already out of reach of home makes for the nearest harbour as far
  as her tank will carry her. Her orders are kept: once she is refuelled, the
  mission or lane carries on. A `sail` you give now is held to the same line.

This is what finally gives the refinery a customer. Before ships, `1 oil → 10
petrol` produced a commodity nothing consumed.

## Tenders

A tender is a slow ship (speed 2) that **rescues your stranded ships by itself**
(Richard 2026-09-14). You build one and leave it in harbour; there is nothing to
order.

- **The distress call.** A ship at sea that cannot make a hex for want of fuel
  calls for help every update. Her history says "distress call sent", and then "a
  tender is on her way".
- **Who answers.** The nearest free tender of yours by sea route, however far: one
  with no orders, not worn out. A tender takes one ship at a time.
- **At the ship.** The tender fills her tank from its hold, and if the sea or a
  battle has left her hull below the limp-home line (the efficiency she needs to
  make one hex an update), patches it up to that line with lcm from its hold, one lcm
  a point. Refitting to 100% is still the harbour's job.
- **Home again.** The tender returns to the harbour it left, stocks up again (400
  petrol and 150 lcm, from that harbour's own stock), and waits for the next call.
  A call that no longer needs answering, because the ship is gone or already has
  fuel, sends it home too.
- A tender can fill its own tank from the petrol in its hold, so it can reach
  ships beyond its own tank, and it still never sails further than it can get back
  from.

**A tender with no orders is on call.** If it finds itself idle at sea, it makes for
the harbour nearest by sea and waits there, answering any call on the way in. The
Fleet panel shows "on call for distress calls" or "answering a distress call from
ship #N".

`supply` and `lane` move cargo **between your harbours**, not to ships at sea. A
tender given either runs that instead and answers no calls, so the Fleet panel
doesn't offer them for tenders. The console still does, and **Stop supply (put on
call)** takes a tender off a supply round.

## Assault ships: settling a coast you do not hold

`explore` only reaches land next to a sector of yours, and ships unload only in
your harbours, so an island that touches nothing of yours cannot be reached any
other way (Richard 2026-09-14).

An **assault ship** carries up to **100 military and 20 civilians**. Load them in
harbour, sail her alongside the coast, and give **`land SHIP x,y`** (or **Land…**
in the Fleet panel) for an unowned land sector next to her. Everyone aboard goes
ashore and **the sector becomes yours**, as if they had explored into it. From
there, `explore` spreads across the island, and a `des harbor` on the coast lets
ordinary shipping reach it.

They bring no food. A sector feeds about 300 × its fertility of people, so a party
of 120 lives off almost any decent ground. On poor ground the reply warns you, and
you should send food before the next update.

### Assault: taking held coast

At **war**, `land SHIP x,y` aimed at the enemy's coast is an **assault** (Richard
2026-09-14). The military aboard go ashore and fight **man for man until one side
is gone**, as the original fought. It happens now, like `fire`, and the reply
gives the result.

- **Defenders:** the military in that sector, **and the defender's military in the
  sectors next to it**. A coast held in depth is much harder to take.
- **Each roll** kills one soldier, an attacker or a defender, weighted by strength.
  A soldier counts `0.5 + 0.5 × efficiency`, using the ship's efficiency for yours
  and each sector's for theirs. Defenders in a **fortress count double**.
- **If you win,** the sector is yours with its designation and its people. The
  survivors garrison it, and your civilians follow them ashore. A tenth of its
  goods are lost in the fighting, roads lose 30% and rail 50%, and its distribution
  centre and delivery orders are cleared.
- **If you lose,** every soldier aboard is gone. The civilians never went ashore.

At peace, held coast is refused: declare war first. Either way it is news.

## Crews

A hull needs people aboard to sail. Merchantmen — fishing, cargo, tanker,
luxury — take **civilians**; everything else takes **military**. The harbour
signs them on while the ship is docked, from the people who are actually on the
quay (the harbour and any warehouse beside it), and they come ashore when it is
scrapped.

**A short-handed ship stays at the quay.** This is deliberate: a fleet competes
with your factories for the same population. Ships are not free once built.

## Fishing

`fish SHIP` sends a boat out on a standing mission: roam the waters near its
home harbour, fish, return when the hold is 90% full, unload, repeat. It picks
richer water more often than poor water.

A boat that put into another harbour for fuel or repairs, and has none of her own
grounds within a leg of it, **goes home and fishes from there**. It doesn't sit at a
strange quay. Deep sea miners do the same.

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
order: it takes the destination and starts at the update. A ship ordered to sail
from port **tops her tank up from the quay first**, so she can leave the turn her
harbour is stocked.

**A `sail` pauses a standing order; it does not end it** (Richard 2026-09-14). A
fishing boat, miner, supply ship, lane or warship on patrol goes where she is sent,
and the order steers her again **once she arrives**. `sail SHIP hold` stops her and
pauses the order until you sail her somewhere. Only `off` (or `lane SHIP none`)
ends one. The `ships` listing and the Fleet panel show a paused order as
*sailing by hand*.

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

## Ferries: people go by ferry

**Cargo ships carry goods, not people** (Richard 2026-09-15: "cargo ships should not be used as busses").
Civilians, military and workers travel by **ferry**: a hold of 300 people, speed 4, a crew of five,
tech 10, and cheap to build (40 lcm, 20 hcm, $800). It moves them between your own harbours with
`load`/`unload`, a `lane`, or a `supply` round, exactly as a cargo ship moves goods. It cannot put
anyone ashore anywhere else: landing on unowned or enemy coast is the assault ship's job.

(The original's cargo ship took 600 civilians and 50 military along with its goods.)

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

`sail SHIP x,y` sends a ship somewhere once; her lane picks up again when she
gets there. `load` and `unload` work **only in
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

`supply SHIP off` takes it off the round. A `sail` order pauses it until she arrives.

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

## War at sea

### When the guns fire

- **At war, automatically.** At every update each of your armed ships, and each
  fort and harbour of yours with guns, fires on an enemy ship it can see and
  reach. So does the enemy. A declared war is the only thing that makes this
  automatic: a warship never starts a fight by itself, so a fishing fleet does
  not blunder into one.
- **By order, at any time.** `fire SHIP x,y [CLASS]` fires on a ship you can see at
  that hex, **now**, not at the update. The target answers straight away, with
  everything of its country that can reach your ship: her own guns, her
  consorts', and any fort or harbour in range. The whole exchange is finished
  before the command returns, and the reply tells you how it went.
- **At peace, firing is allowed, and remembered.** Firing on a country you are
  not at war with does not declare war. It **marks the ship that fired**: for 3
  updates that country's warships and coastal guns will engage her on sight. The
  rest of your navy is not a target, and no war has started. The `ships` listing
  and the Fleet panel show a marked ship in red.

### What you can aim at

A **contact** from this update or the last, at the hex where it was seen, or a
surface ship inside your own ship's sight. A contact is where a ship *was*; if
she has sailed since, there is nothing there to hit. Contacts are drawn on the
map as diamonds: solid when fresh, dashed when a sighting is older.

**Submarines** can only be hit once detected, and only by a destroyer or frigate.
A battleship cannot touch one, and neither can a fort.

### What a salvo does

`guns × 3 × efficiency × (1 + tech/200, at most ×2) × a roll of 0.5–1.5 ÷ (1 +
armour/100)`, taken off the target's efficiency. The number of guns that fire
is the smallest of what the class mounts, the guns aboard, and the shells to
feed them: **one shell per gun per salvo**. A destroyer's 60 shells are six
salvos.

Damage is efficiency, so a battered ship is slow as well as weak, and **a
harbour repairs it** at the same 20 points an update as fitting out.

### Ammunition

A docked warship **rearms from the harbour's own stock**: guns up to what she
mounts and shells up to her magazine. Send the harbour shells and guns first, by
road, rail, a lane or a supply ship.

### Sinking and salvage

**At 10% efficiency or less a ship sinks.** Her crew and fuel go down with her.
Of her cargo, a quarter is lost in the fighting. **Your ships in the same hex
salvage the rest**, as far as their holds have room and they may carry it; what
nobody can take goes down too. A sinking is news.

### The coast

A **fortress or harbour** with guns, shells and military fires on hostile
surface ships within **3 hexes**: one gun for every 5 military, at most 20 guns.
It uses its own shells, as a ship does.

### Blockades and trains

- A warship on **`blockade`** holds a station. A hostile ship that comes within
  **1 hex** of her is stopped there, whether sailing at the update or by an order
  given now, and one already inside cannot leave.
- A warship on **`interdict`** holds a station and, finding no ship to shoot,
  shells the nearest **enemy train** she can see and reach: rail cargo held on a
  line near the coast. Each gun destroys 10 units. Trains do not fire, so this
  happens only at war.

## Missions for warships

A mission says **where** a warship goes. Whether she fires is still up to the war.

| order | what she does |
|---|---|
| `patrol SHIP x,y x2,y2 …` | walks the points in order, and round again |
| `search SHIP` | wanders the water within 10 of home, at most 4 hexes a leg, going where you have not looked lately. Deliberately unpredictable |
| `escort SHIP OTHER` | stays with one of your other ships |
| `blockade SHIP x,y` | holds that hex and stops hostile ships next to it |
| `interdict SHIP x,y` | holds that hex and shells enemy trains in reach |

Each takes `off` to stop. A `sail` order pauses it until she arrives.

**Every mission comes home for supplies, and goes back out by itself.** Home is
the harbour she was in when you gave the order, or the nearest of yours she can
reach. She breaks off when:

- her shells fall below a third of the magazine;
- her fuel falls below a quarter of the tank, or below what it takes to get home
  with a quarter to spare;
- she is short-handed;
- the sea or a battle has worn her to 60%.

In harbour she rearms, refuels, signs on crew and refits, then returns to her
patrol, station, charge or search without another order.

### Old games

A game keeps its rules for life. **A game created before combat has none of
it**: nothing fires and `fire` says so. The deity's **Reload rules** brings it
in.

## Being seen

A ship lifts the fog around it: everything within its sight radius is visible
to you while it is there. That makes even a fishing boat a scout.

It works both ways — your ships are what other people's radar and lookouts
detect. Detection is probabilistic, so a ship may slip past a radar one update
and be spotted by it the next. Held cargo has a low signature of its own, so
being seen does not necessarily mean the cargo was.
