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

## What is not here yet

Fighters and interception, escorts, transports and paradrops, carriers as floating
airfields, mines from the air, and then missiles and satellites. This slice is bombing,
reconnaissance and the flak that answers them.
