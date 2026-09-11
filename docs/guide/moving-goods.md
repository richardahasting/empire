# Moving goods

Making things is half the game. The other half is that they are made in the
wrong place.

## Mobility

Every sector accrues **60 mobility per update**, capped at **127**. Efficiency
does not change the rate. Mobility is what pays for movement, and it is the
thing you run out of.

Moving one weight-unit **into** a sector costs, before discounts:

| terrain | cost |
|---|---|
| plains, wilderness | 0.4 |
| forest | 0.6 |
| swamp | 0.8 |
| mountain | 2.4 |

A designated sector gets cheaper as it improves — the cost halves between 0%
and 100% efficiency. Roads and rail discount it further, and **rail discounts
movement into a sector whether or not a train is involved**.

**The sending sector pays for the whole route.** Not each sector along the way.
This matters: a busy distribution hub used to be drained of mobility by
everything passing through it, and now is not.

## Weight, and why warehouses matter

Mobility is charged per **weight-unit**, and weight depends on where the goods
are *leaving from*:

- Goods leaving a **warehouse** or a **city** weigh a **tenth** as much.
- Civilians leaving any efficient sector weigh a tenth as much.
- Gold bars are heavy (50) everywhere; gold dust is 5; most things are 1.

So a warehouse is not really storage. It is a **loading dock** — its whole
purpose is that things leaving it cost a tenth of the mobility. Putting one
where your goods pile up is usually worth more than another factory.

## Four ways to move something

**1. By hand — `move`**

`move food 0,0 2,0 500` moves it now, this command, and pays **full price**: no
distribution discount. Good for one-off corrections, expensive as a habit.

**2. Thresholds and distribution centres — `thresh`, `dist`**

This is the workhorse, and it is a standing arrangement rather than an order.

Point a sector at a distribution centre with `dist`, then set thresholds with
`thresh`. Each update, for each commodity:

- stock **below** the threshold: the sector asks the centre for the difference,
- stock **above** it: the surplus is offered to the centre.

Mobility for distribution is charged at a **tenth** of the hand-move rate. This
is the single largest discount in the game.

Two limits apply. **Reach**: a shipment travels at most `3 + 0.02 × tech`
sectors per update, plus up to 2 more along roads. And a shipment that cannot
complete **holds in place** — it is not returned and not destroyed; it sits
where it stopped as a parcel and continues next update.

Designating a sector now wires it into distribution automatically: it is
pointed at the nearest hub it can reach and given sensible thresholds for its
type. It only ever fills in blanks, so a centre or threshold you set yourself
is never overwritten.

**3. Delivery orders — `deliver`**

`deliver food 1,0 e 200` means: every update, if this sector has more than 200
food, push the surplus one hex east.

One hop per update, to a fixed direction, at a discounted mobility rate.
Chaining them builds a conveyor — but each link advances **one hop per
update**, because the receiving sector acts on its own order next update. A
five-sector chain takes five updates to fill, every time.

**4. Rail — `rail`, `railship`, `raillane`**

Rail needs tech 60. Lay track with `rail`, build `depot` sectors as endpoints,
then either ship a batch with `railship` or set up a standing run with
`raillane`. Capacity depends on the rail level and the efficiency of both
depots.

Rail can cross water as a **bridge**: order rail on a sea hex adjacent to your
land and the neighbouring sector with the most rail sponsors it, paying the
materials. A mountain needs a **tunnel**, which also costs extra.

## When everything wants the same thing

A source's stock, a sector's mobility and a rail line's capacity can all be
claimed by more demand than they can meet. It is settled:

1. **Proportionally** — every claim is scaled by the same fraction.
2. Ties to the **lower commodity priority**: food first, then petrol, lcm, hcm.
3. Anything still tied, by **seeded randomness**.

There is no queue and no first-come-first-served, because there is no order in
which orders arrive. Two sectors asking for the same grain both get some.

## Getting it wrong

The usual failures, in the order people hit them:

- **Nothing moved.** The sending sector had no mobility left. Mobility caps at
  127, so a sector that has been idle is not storing up more.
- **Only some of it moved.** Something else claimed the same budget, and the
  contention rule split it proportionally.
- **It moved one hex and stopped.** That is a delivery order behaving
  correctly, or a shipment that ran out of reach and is now holding as a parcel.
- **It never arrives.** Reach is 3 sectors plus tech and roads. Long hauls need
  rail, or intermediate hops.

Each sector records what happened to it every update, in order. When movement
is not working, that history names the reason.
