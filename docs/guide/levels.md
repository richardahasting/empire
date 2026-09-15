# Levels

Four national levels: **tech**, **research**, **education** and **happiness**.
They behave in two quite different ways, and the difference explains most of
what is confusing about them.

## Stocks: tech and research

Tech and research **accumulate**. What you produce each update is added to the
level and stays there.

Two things temper it:

- **Diminishing returns.** Above an easy threshold, a gain of *p* is worth
  `easy + log(p − easy + 1)` rather than *p*. Doubling your research output
  does not double your progress. The level is capped at 250.
- **Ageing.** Every level decays by about **1% per 96 ETUs**. At 60 ETUs an
  update that is a little under 1% each update, forever. A country that stops
  researching slides backwards.

Tech gates what you can build — rail at 60, most warships at 40 and above — and
**scales every production rate in the game**. Tech is not a side activity.

**Research is room to grow.** In the classic and blitz worlds a sector's
population ceiling is the flat 1,000 scaled by research, the original's
RES_POP rule: `0.4 + 0.6 × (50 + 4R) / (200 + 3R)` — **550 a sector at
research 0**, 790 at 40, approaching 1,000. The census `cap` column and the
sector inspector show the ceiling for your research now. Teaching and sandbox
worlds keep the flat ceiling, so research there is only what tech needs.

## Moving averages: education and happiness

Education and happiness are **not** stocks. They are moving averages of a
*rate*:

```
rate  = produced × consumption / (civilians × ETUs)
level = (level × average_etus + rate × ETUs) / (average_etus + ETUs)
```

The consequences are worth stating plainly:

- **They are per-civilian.** A country that doubles its population and keeps the
  same schools sees education fall. You are producing the same amount for twice
  as many people.
- **They drift back down when you stop.** Not decay in the tech sense — the
  average simply forgets, over `average_etus`.
- **They cannot be stockpiled.** There is no point producing a burst of
  education and then stopping.

**So education has an upkeep**, and it grows with your population. At 60 ETUs,
holding a level takes roughly this many points an update (a point is 1 lcm and $9
in a school; a full school of 1,000 makes 600):

| to hold | per 100,000 civilians |
|---|---|
| 5 | 50 |
| 10 | 143 |
| 20 | 462 |
| 44 | 1,450 |

Above 5 each point buys less. The dashboard shows it under education as
**made/needed to hold**: the points the next update makes against the points that
would hold the level where it is, in red when you are short. The terminal client's
`projection` says the same. Schools that run out of lcm make nothing, and a
threshold below one update's use (600 for a full school) lets them run dry.

Happiness also scales every sector's work pool, from **0.5× to 1.2×**. Unhappy
countries do up to half as much work with the same people, everywhere at once.
It is the cheapest thing to neglect and one of the most expensive.

## Education gates the ladder

Education gates research and tech production — you cannot research your way up
without schooling first. The ladder is `school` → `university` for education,
then `research_lab` → `technical_center`, and the labs consume gold dust, oil
and lcm, so the chain reaches back into your mines.

## Technology bleed

A country below **a fifth of the leader's tech** has a **20% chance each
update** of closing **a third of the gap**, free.

Falling behind is not fatal, and running away with a tech lead does not
guarantee keeping it. If you are far behind, this is working in your favour
whether or not you do anything.

## What this means in practice

Early on, the levels feel like a distraction: you have food to grow and iron to
dig. But tech multiplies *every* production rate and ageing runs whether you
participate or not, so the country that never builds a school is slowly getting
worse at everything while its neighbour gets better.


## How you are doing

The games page has a **Nations** button. It ranks every country by a composite
of population, total efficiency, tech, treasury and territory, weighted by
`scoring.weights`, with a large bonus for simply still existing.

**Your own figures are exact.** Everyone else is shown as well as the game
allows and no better — by default a rank and a word (`struggling`, `holding`,
`strong`, `leading`) and no numbers at all.

That is deliberate. The score is worked out from the whole world, so publishing
it in full would hand you an exact census of every rival — and territory and
population are precisely what radar, scouting and map memory exist to make
expensive to learn. A board that gave them away would quietly undo a large part
of the game.

A deity can set `scoring.visibility` to `exact` for an open game, `rank` for
bare positions, or `none` for no board at all.

**BTUs are never part of it.** There is no advantage in hoarding them.
