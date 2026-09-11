# Empire

Empire is a game of running a country: growing people, feeding them, digging
things out of the ground, turning those things into other things, and moving
them to where they are worth something. There is a map, and eventually there
are neighbours.

It is played in **updates**. You give your country standing orders and spend
your attention; then, on a schedule, the whole world advances one step at once.
Nobody moves "first". What you set up before the update is what happens in it.

## Start here

- **[Your first turn](first-turn.html)** — signing in, what you are looking at,
  and the first handful of things to do.
- **[The update](the-update.html)** — what actually happens when the world
  advances, in the order it happens. Worth reading early; most confusion about
  Empire is really confusion about the update.

## Then

- **[The economy](economy.html)** — designations, efficiency, work and
  production. How a sector turns people into goods.
- **[Moving goods](moving-goods.html)** — mobility, thresholds, distribution
  centres, delivery orders, roads and rail. Getting things from where they are
  made to where they are needed.
- **[Ships](ships.html)** — harbours, hulls, fuel, crews, fishing and cargo
  runs.
- **[Levels](levels.html)** — tech, research, education and happiness: what
  they do and why they fall when you stop paying attention.
- **[Command reference](commands.html)** — every command, what it takes and
  what it costs.

## Running a game

- **[For the deity](deity.html)** — creating a world, what each generation
  parameter does, seating players and bots, and ending a game.
- **[POGO, the deity](pogo.html)** — the deity's own country at the origin: what
  it can see, what it can change, and what changing things costs.

## A note on the rules

Empire is a re-implementation of a game from the 1980s. Where the original's
behaviour is known it has been matched; where it is not, a decision was made
and marked. Every rule and every number lives in one file, `config/schema.yaml`,
annotated **KNOWN**, **GUESS** or **NEW** — so if a rule here surprises you,
that file will tell you whether it is faithful, inferred, or invented.

Numbers in this guide are the defaults. A game is created from a *preset* and
the deity may change most of them, so your game may differ.
