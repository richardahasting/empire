# The market

Countries trade goods on a public **market**, as the original game's `MARKET` option had it
(issue #141; `sell`, `buy`, `reset` and `market`). Nobody sails anything: goods go up on the
market out of a harbour or warehouse, and come down into the buyer's.

## Selling

- **`sell COMMODITY SECTOR N PRICE`** lists a lot: `N` of the goods, at `PRICE` dollars **a
  unit**, from a **harbour or warehouse** of yours at **60%** or better that has some mobility.
  A negative `N` sells all but that many (`sell food 3,4 -500 1.5` keeps 500).
- The goods **leave the sector the moment you list them** and wait on the market. Civilians
  cannot be sold; anything else can, military included. No unit is dearer than **$1,000**.
- A people you conquered and do not hold down (fewer than one soldier for every ten of them)
  will not hand their goods over.

## Buying

- **`market`** shows the cheapest lot of each commodity; **`market food`** every lot of food;
  **`market all`** everything. The market is public: everybody sees every lot, who is selling
  it and who is winning it. Only the seller sees where it came from.
- **`buy LOT PRICE SECTOR`** bids `PRICE` a unit for the whole lot, to be delivered to a
  harbour or warehouse of yours (60%+) with the room for it. A bid must beat the lot's price
  by at least **$0.05** a unit — the first bid too, since the asking price is the price.
- You must be able to pay for it **on top of every other lot you are winning**. Nothing is
  taken until it sells.

## The sale

A lot sells **4 updates** after it was listed (or last re-priced), at the update, to whoever
holds the high bid. The buyer pays the price, the seller is paid it, and the goods appear in
the buyer's harbour or warehouse. A lot **nobody bids on never sells**: it stays up until its
seller takes it back.

A high bid placed when the lot would sell at the very next update pushes the sale back one
update, so the others can answer — the original's "last five minutes".

If the sale cannot go through — the buyer cannot pay, or the harbour or warehouse they named
is no longer a working one of theirs with the room — the lot goes **back on the market** at
the price it reached, with nobody bidding, and both of you are told.

## Re-pricing and taking it back

**`reset LOT PRICE`** lowers the price of a lot of yours that nobody has bid on, and starts its
time again. **`reset LOT 0`** takes it back: the goods return to the sector they came from,
which must still be a working harbour or warehouse of yours. Once somebody has bid, the lot
is out of your hands until it sells.

## Ships, planes and land units

Whole ships, planes and land units are traded too (the original's `set` and `trade`).

- **`set ship|plane|unit IDS PRICE`** puts them up for sale, at `PRICE` dollars each, in
  whole dollars: `set ship 4,7 25000`. **`set ship 4 0`** takes it off. Setting a price again
  starts the lot afresh: any bid on it is void, as in the original.
- Nobody aboard may be a **civilian** — people are not for sale — and a land unit aboard a
  ship must be put ashore first.
- **While it is for sale it does nothing**: a ship holds where she is (no sailing, no lane, no
  mission, no tender calls, her guns silent), a plane neither flies nor rises against raids,
  a unit neither marches, loads nor fights. Any order naming it is refused until you take it
  off the market.
- **`trade`** lists everything for sale: what it is, its tech and condition, what a ship or
  unit carries, the price, and who is winning it.
- **`trade LOT PRICE [SECTOR]`** bids, in whole dollars, **more** than the lot's price, which
  you must be able to pay on top of everything else you are winning. A **plane** needs an
  **airfield** of yours to go to, a **unit** a **headquarters**, at 60% or better; a **ship**
  changes hands where she lies.
- It sells **4 updates** after it was set, at the update, to the high bidder, who pays the
  price; the seller keeps **99%** of it. A ship goes with her hold and whoever is aboard her,
  and none of her old orders. A plane flies to the buyer's airfield; a unit goes to their
  headquarters with what it carries. If the buyer cannot pay, or no longer has the place
  they named, the lot is taken off the market and the thing stays with its seller.

## How this differs from the original

The original timed lots by the **wall clock** (two hours, settled every five minutes) and could
sell between updates. This game's engine has no clock, so a lot's time is counted in updates
and it sells at the update. In the original a sale that fell through told both sides the goods
stayed on the market and then deleted them; here they stay. Object trade is timed in updates
too (`trade_delay_updates`, 4).
