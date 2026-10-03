# The air force: planes

A **plane** sits on an airfield of yours and flies a sortie when you send it: out to the
target and home again the same turn. It carries nothing between flights — it takes its
petrol, and its bombs, off the field each time it goes (issue #262, the original's
`pln_equip`). Richard, 2026-09-15: the original is the default.

## Building one

- Designate a sector **`des x,y airfield`**. Keep **petrol and shells** there: a plane with
  no fuel on the field cannot take off, and a bomber with no shells has nothing to drop.
- **`build x,y bomber`**, or **Build plane…** on the airfield's right-click menu. It is laid
  down at **10%** for a tenth of its materials and cash, and **fits out on the field** —
  about 2 points an ETU while it sits on a working airfield of yours.
- A plane costs **upkeep** every update, like a land unit. Unpaid, it loses condition.

| class | tech | bombs | accuracy | strikes | petrol a sortie | what it is for |
|---|---|---|---|---|---|---|
| bomber | 90 | 8 | 40% | 8 hexes | 3 | strategic raids: wrecking a sector wholesale |
| tactical bomber | 110 | 4 | 70% | 6 hexes | 2 | pinpoint raids: what is standing in the sector |
| reconnaissance | 70 | — | 50% | 10 hexes | 2 | seeing what is there |

**Strikes** is half the class's range: the range is the round trip, as the original had it,
so a bomber that flies 16 reaches 8 hexes out. A plane built above its class's tech is a
little better at everything and flies a little further.

## Sorties

- **`bomb PLANE x,y`** (or **Bomb…** in the Air force panel) flies a **pinpoint** raid;
  add `strategic` — `bomb 3 4,-1 strategic` — for the other kind.
- **`recon PLANE x,y`** flies over and puts the sector on your chart: what it is, whose it
  is, and how many people and soldiers were standing in it. It drops nothing.
- Bombing is at **war** only. Reconnaissance is not: you may fly over anybody, and their
  guns will still fire at you.

### What a raid does

`roll(load) + 1` bombs fall. Each does `roll(6)`, and then **8** on a one-in-ten roll, **5**
when it hits by the plane's accuracy, or **1** when it does not. The total **doubles** when
the plane is the right kind for that raid: a bomber flying strategic, a tactical bomber
flying pinpoint. The wrong pairing does about half the damage.

The sector takes it the way it takes sabotage and shellfire: that percentage off its
**efficiency, roads, rail, mobility and every commodity in it**. Bombing takes no ground —
it is what you do before an attack, not instead of one.

### Flak

Whatever you fly against shoots at you (the original's `ac_flak_dam`). The sector's guns, up
to eight of them, doubled for its owner's tech, fire once: the damage is `(roll(8) + 2)`
times a multiplier that rises steeply with **guns minus your plane's defence**. A tactical
bomber flies low and is a step easier to hit than anything else.

- A plane below **10%** is **shot down**.
- One under **80%** may **turn back** before it reaches the target, with a chance of
  `(80 − efficiency)/100`. A shot-up plane left on its field is fitted out again.

So guns in a sector are worth keeping even when nobody is marching at you, and a bomber's
first raid against a well-defended sector is rarely its last problem.

## Fighters, interception and escorts

Fighters are the original's (`plane.config`), as they were:

| class | tech | attack | defence | range | petrol | what it is for |
|---|---|---|---|---|---|---|
| biplane fighter | 50 | 1 | 1 | 4 | 1 | the first fighter: it rises against raids |
| fighter | 80 | 4 | 4 | 8 | 1 | rises against raids, escorts |
| jet fighter | 125 | 14 | 14 | 11 | 3 | the same, much harder |
| VTOL jet fighter | 195 | 17 | 17 | 14 | 3 | the same, harder still |
| escort fighter | 90 | 5 | 5 | 15 | 2 | escorts only: it does not rise against raids |
| jet escort | 160 | 10 | 10 | 25 | 3 | escorts only, a long way |

**Interception.** A raid flies hex by hex from its field to the target. Over each sector
held by a country **at war** with the raider, that country's fighters rise: those at **40%**
or better, on an airfield of theirs that is at least 40%, with the range to reach that
sector and come back, and with their petrol on the field (the sortie takes it). The newest
go up first, as many as the raid has planes **and one more**, and each fighter rises only
once a raid. At peace nobody rises: reconnaissance over a neighbour still meets their
guns, but not their fighters.

**Escorts.** `bomb 3 4,-1 escort 5,6` takes fighters or escort planes along. Each must be
at 40% or better, on a field of yours **within 4 hexes** of the bomber's, with the range
to fly to the bomber's field, on to the target and back, and its petrol on its own field.
A bad escort refuses the whole sortie before anything leaves the ground. Escorts fight the
interceptors **first**; the bomber meets only whoever is left.

**Dogfights** (the original's `ac_dog`). The raid plane brings its attack (its defence if
it has no attack), the interceptor its defence, each by its efficiency and never less
than half its class's defence. Those set the odds; then four rolls of 20, plus one, exchanges
follow, each costing one side a point, until they run out or either is down to the
minimum. The two lists pair off round and round until both have fought. As with flak, a
plane under **10%** is shot down, and one under **80%** may turn back. A bomber that turns
back drops nothing; flak over the target comes after the fighters.

**Air defence** (the original's `mission … a`). `mission PLANE air x,y [RADIUS]` puts a
fighter on air defence around a point no further than it strikes, out to a radius no
further than that either (0, or nothing, is as far as it reaches). At war it rises not only
over your own land but over **any** sector in that area — a neighbour's, the sea's edge,
the raider's own — when a raid flies over it. `mission PLANE off` takes it off; it still
rises over your own land, as every fighter does.

## Transports: fly, drop and paradrop

The original's transports (`plane.config` `tr` and `jt`):

| class | tech | load | range | petrol | |
|---|---|---|---|---|---|
| transport | 85 | 7 | 15 | 3 | cargo, paratroops |
| jet transport | 160 | 16 | 35 | 4 | cargo, paratroops |

Several planes may fly a sortie together if they are on the **same field**: name them with
commas, `fly 4,5,6 …`. Each takes its petrol off the field. A plane's **load** is in pounds:
it carries `load ÷ the commodity's weight` of it — guns weigh 10, gold bars 50, most things 1.

- **`fly PLANES x,y [COMMODITY] [escort E,E]`** flies **one way** to an airfield of yours at
  60% or better, and they stay there. Any plane can fly — that is how you move an air force
  — but only a transport carries anything, and on a flight to land it carries **twice** its
  load. Escorts fly along and land with them.
- **`drop PLANES x,y COMMODITY [escort E,E]`** drops a transport's load on a sector of yours —
  a cut-off garrison, a starving city — and flies home. Nothing lands.
- **`paradrop PLANES x,y [escort E,E]`** carries the field's **soldiers**, one per pound of
  load, onto a sector **not yours** and flies home. At war with its owner, or onto land
  nobody holds. Not onto mountains or the sea, a capital, a fortress or a wasteland. The
  paratroops fight like any attack, but alone: nothing supports them, while the defender's
  guns fire as usual. Survivors take the sector; a lost drop leaves nobody.
- **Civilians** fly only from land whose own people they are, and only into such land of yours.
- On the way, enemy fighters rise against them as against any raid, and the flak over a drop
  zone fires at each transport. **What a plane that is shot down or turns back carried is
  lost** — it left the field when the plane took off.

## What is not here yet

Stealth and missile interceptors, carriers as floating airfields, mines from the
air, and then missiles and satellites.
