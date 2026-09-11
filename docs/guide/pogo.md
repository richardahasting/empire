# POGO, the deity

Every game has one more country than it has players. It sits at the origin, owns
two sectors, never does anything, and belongs to whoever runs the game.

It is called **POGO**, after the deity's country in the original Empire.

## Why a country at all

The deity could have been a menu bolted on beside the game. Making them a
country instead means the machinery already there does the work.

**The origin.** Every coordinate a player sees is worked out relative to their
own capital — your capital is `0,0`, and everything else is counted from it.
POGO's capital *is* absolute `0,0`, so relativising against it is very nearly
the identity: the deity reads the world in the same numbers the database uses,
by the ordinary rule rather than a special case.

Very nearly, because of wrapping. On a world that joins at the edges, a hex more
than half a map away is closer the other way round, so `10,2` on a 16-wide torus
relativises to `-6,2`. That is right for a player and useless for a tool whose
job is editing a named sector, so **the deity's view reports absolute
coordinates outright**. What you see is what you type.

**Sanctuary.** POGO's two sectors are in sanctuary, which already means
invisible, invulnerable, and unable to act beyond themselves. The deity's
presence on the map costs the players nothing, and no rule had to be written to
make that true.

## What POGO is not

- **Not a seat.** It never appears in the roster, never counts toward the number
  of countries a game has, and never holds up the starting bell.
- **Not a player.** It has a capital and a treasury because every country does.
  It does not act, and nothing it owns is reachable.
- **Not country 0.** In the original the deity was country 0. Here it is added
  last, after the players, so that seating a deity never renumbers anybody. The
  property that mattered — the capital at the origin — is kept.
- **Not retrofitted.** Games created before POGO existed do not have one, and
  the deity's view and edits will say so rather than inventing one.

## What the deity can do

Beyond the game-level controls on the games page — creating, starting, pausing,
scheduling, deleting — POGO can reach inside a running world.

### See everything

The whole map, unfogged, in absolute coordinates. No map memory, no contacts,
no adjacency rule: every sector as it actually is.

This is a separate thing from a player's view, deliberately. It is not the
player view with the fog turned off by a flag, because a flag like that is
eventually passed by accident.

### Edit a sector

By absolute coordinates. Any of: owner, designation, efficiency, mobility,
stock, the five resource endowments, and road, rail or radar level. Anything
left out is left alone.

### Edit a country

Cash, BTUs, tech, research, education, happiness, and the sanctuary and
bankrupt flags.

### Reissue a bot's token

An agent seat is given a bearer token once, and only its hash is kept — so an
agent that lost its token previously had no way back in, and the only fix was a
second country and a wasted seat. The deity can now mint it a fresh one.

A person who loses access does not need this: they ask for another magic link.

## POGO's screen

There is a **POGO** button on every game's card. It opens the deity's own screen,
which does three things.

**Look through somebody.** The selector across the top is *POGO + every country
in the game*. POGO shows the whole map in the map's own coordinates. Any other
country shows the world **as that player actually sees it** — their fog, their
map memory, their contacts — which is the only way to answer "why can they not
see that" without guessing.

It is a table rather than the game's map, on purpose: this is a tool for finding
one sector and changing it, and a list you can filter beats a picture you have
to hunt across. Filter by coordinate, designation, terrain or owner.

**Change a sector, or a country.** Pick a sector from the table to edit it;
pick a country from the dropdown for cash, BTUs and tech. Blank fields are left
alone.

Editing is only offered while looking through POGO. A player's view reports
coordinates relative to *their* capital, and a sector is changed by its place on
the map rather than by where it happens to sit relative to somebody — offering
an edit box against a relative coordinate would be offering a mistake.

**See what has been changed.** Every hand edit is listed at the foot of the
screen, oldest at the bottom.

## What editing costs

Every edit is a write **outside the update**, and that is not free.

The update guarantees two things, and checks them both every time. **Conservation**:
each commodity's new total equals the old total plus what was produced, minus
what was consumed and destroyed — nothing appears from nowhere. And
**determinism**: the same world, config and seed always produce the same result,
which is what makes a game replayable from its starting seed and its commands.

A deity conjuring ten thousand iron breaks both. The world no longer replays,
and the state hash stops being an audit of anything.

That is a fair price for a tool whose entire purpose is rescuing a game that has
gone wrong. It should never be a silent one, so **every edit is recorded** —
what was changed, what it was before, who did it, and at which update. When
someone later asks why a game will not reproduce, there is an answer.

## Provenance

**GUESS.** Richard remembers POGO as the admin user in the original, with a
sanctuary at true `0,0`, able to see the whole map and to edit sectors,
countries and credentials — offered with "I might not be" attached.

`docs/original-rules.md` is carefully sourced from the Wolfpack tree and says
nothing about the deity or its `edit` command, so none of this is marked KNOWN.
Anyone with the original source to hand should read `src/lib/commands/edit.c`
and settle it — whatever the original chose about what a deity may change is a
free answer to a question we have guessed at.
