# For the deity

The deity creates worlds, seats players and ends games. Everything here is
admin-only and lives on the games page.

## Presets

A game is built from a preset, which supplies every rule not on the create
form — the economy, which units exist, how long an update is.

| preset | map | update | seats by default | notes |
|---|---|---|---|---|
| teaching | 16×16 | 1 hour | 4 | economy only, no units, sanctuary relaxed |
| sandbox | 32×32 | manual | 1 | everything unlocked, huge stocks |
| blitz | 32×32 | 15 min | 8 | one evening, ends after 20 updates |
| classic | 128×64 | daily | 40 | months long |

The seat count is a default: the create form overrides it, up to 64.

**A game keeps its rules for life.** The preset is copied into the game when it
is created, so shipping a new rule does not change a running game. `Reload
rules` replaces that copy with the preset as it is now, keeping the map and the
stocks.

## Creating a world

The form overrides the preset's map. Anything left blank keeps the preset's
value, and the placeholders show what that value actually is.

**Countries** — how many seats the world is built with, up to **64**. They are
created numbered — `emp1`, `emp2` and so on — and named by whoever claims them.
This also sets the game's country limit, so it is the one number that decides
both how many can play and how many ever could. The game waits in setup until
they are all taken, or until you start it; see The starting bell below.

**Seed** — the one number every random draw comes from. Not just the map: each
update derives its own randomness from it, so the seed fixes the whole game's
random history. The same seed with the same settings and the same moves replays
an identical game, which is what makes the state hash meaningful. Leave it
blank for a world nobody has seen.

**Width and height** — up to 2048 a side and 2,097,152 sectors in all. The
largest worlds take about 82 seconds an update.

**Water %** — how much of the map is sea. The rest is land, split between the
countries' own islands and whatever extra islands are needed to fill the quota.

**Island size** — average sectors in one landmass. Small scatters the land into
an archipelago; large gathers it into a few continents that countries may end
up sharing.

**Spike (0–100)** — coastline jaggedness. High values grow land off the newest
edge, making fingers, peninsulas and inlets; low values grow it evenly, making
round blobs.

**Capitals apart** — the fewest sectors between any two capitals. Raise it to
keep players apart early. Raise it too far and there is nowhere to put
everyone: the world is refused at creation, with a message saying how many
would fit at that spacing and what the widest workable spacing is.

**Wrap east–west / north–south** — whether the map joins at the edges. With
both on the world is a torus, which is what the original did. A north–south
wrap needs an even height.

**Land mix** — what the land is made of: wilderness, plains, forest, mountain,
swamp. These are **weights, not percentages** — they are scaled to fit, so only
the ratios matter, and a terrain left blank keeps the preset's share rather
than dropping to zero.

This is the real economic lever. Resources are a pure function of terrain:
mountains carry the minerals, gold and uranium; plains carry the fertility;
swamp carries the oil. **Raising the mountain weight is the only way to make a
mineral-rich world** — there is no separate ore-abundance setting.

## What the generator does with those numbers

One island is grown per country from its capital, then extra islands until the
land quota is met. Capitals are placed by rejection sampling at the minimum
distance; if the request is impossible it is refused up front rather than
discovered slowly.

Each land sector then draws a terrain type from the land mix, an elevation from
that terrain's range, and five resource endowments (fertility, minerals, gold,
oil, uranium) from per-terrain distributions.

Two things worth knowing:

- **Resources are drawn independently per sector.** There are no ore veins or
  oil fields — a rich mountain says nothing about the mountain beside it. The
  sea is the exception: fishing grounds are drawn per region of about 4×4, so
  good water comes in patches.
- **Capitals are forced to plains**, so every country starts on high fertility
  and almost no minerals regardless of the island around it.

## Seating players

**Countries is a number**, not a list of names. Ask for eight and the world is
built with eight seats called `emp1` … `emp8`, up to a maximum of 64. Each
player names their own when they claim it.

## The starting bell

A new game is created in **setup**, not running. No updates are scheduled and no
commands are accepted, so nobody gains anything by joining early.

The bell rings on whichever comes first:

- **the last seat is claimed** — automatic, no action needed, or
- **you press Start** — for when somebody is not coming. The empty seats stay
  open and can still be claimed afterwards.

Either way the game moves to `running` and the first update is scheduled from
that moment.

A seat is claimed by a player naming it, on the public join page — they need no
account first, and the emailed link that follows is what proves the address and
turns the hold into a seat. A held seat does not count as filled, so the
starting bell cannot be rung by somebody who typed an address they do not own. **Add country** seats
someone in a game already running, beyond the seats it was built with. The capital goes on
unowned land at the minimum distance from every other capital; if there is no
such sector the request is **refused with the reason** — how many land sectors
are owned, how many are merely too close, and whether the fix is a lower
spacing or a bigger world. The map is never changed to make room.

Two kinds of seat:

- **Person** — left open, claimed through the normal Join button.
- **Agent** — bound to a fresh bot account, returning a bearer token. Agents
  are ordinary players speaking the same API; the only thing they cannot do is
  click a link in an email, which is why the token is minted directly. **It is
  shown once** — only its hash is stored.

## Running a game

- **Update interval** — from manual to daily, changeable at any time.
- **Pause / Resume** — stops and restarts the schedule.
- **Run update** — forces one immediately.
- **Reload rules** — replaces the game's rule snapshot with the preset as
  shipped now. Keeps the map and the stocks.
- **Seed fishing grounds** — gives an existing game's ocean its fertility, for
  worlds generated before fishing existed.
- **Delete** — removes the game and everything in it: the map, the countries,
  the ships, the orders and the whole update history. It asks you to type the
  game's name, because there is no undo and the history goes with it.

## Reaching inside a running game

Everything above is game-level. Changing a sector or a country in a world that
is already being played is POGO's business — the deity's own country at the
origin, which sees the whole map and can edit what it sees. See
**[POGO, the deity](pogo.html)**.

## When an update fails

A failed update is rolled back whole — nothing is written — and the schedule
**pauses** rather than continuing on a world that might be wrong. Two invariants
can cause this: conservation (a commodity's total did not match what was
produced and consumed) and determinism. Both mean a bug, and the paused game is
the evidence.
