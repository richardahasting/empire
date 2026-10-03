# Mines

Mines are the original's (issue #71; `mine`, `lmine`, `drop`, `sweep`, and what a ship or a unit
strikes on the way). There is one count of them a sector: **sea mines** on the sea, **land
mines** on land. Nobody owns a mine. A ship strikes anyone's, yours included; a unit strikes only
another country's.

## Sea mines

- **`lay SHIP N`** — a **destroyer**, **submarine** or **minesweeper** at sea (not in a harbour)
  lays up to N mines where she is, **a shell a mine**, from her own magazine. Her standing
  mission ends.
- **`drop PLANES x,y shell`** onto the sea — **naval planes** carry **twice** their load in shells
  and drop them as mines, then fly home. Enemy fighters may meet them on the way, and a plane
  shot down or turned back takes its mines with it.
- **Striking one.** A ship entering a mined sea hex strikes a mine with chance **N/(N+20)** for N
  mines there — five mines, one in five; a hundred, five in six. It costs her **21 + roll(21)%**
  of her hull, divided by 1 + armour/100, and she **stops there**. A hull taken to the sinking line
  goes down with everything aboard. Harbours hold no sea mines. This happens when she sails by
  hand and when she sails at the update.
- **Sweeping.** A **minesweeper** sweeps every sea hex she enters: five tries, each clearing a mine
  two times in three, and each swept mine is a shell back in her magazine. A strike costs her half.
  She tests her own luck after sweeping, as well as with the others.
- **`sweep PLANES x,y`** — naval planes fly over the sea to x,y and home, and in each sea hex on
  the way each clears at most one mine, with chance (100 − accuracy)/100.

You do not see sea mines, not even your own; you remember where you put them.

## Land mines

- **`lmine UNIT N`** — an **engineer**, ashore, in land of yours, lays up to N land mines: a shell
  (its own, then the sector's) and a point of its mobility each.
- They are the **land's**: they belong to whoever's people live there (the old owner, while a
  conquered people are held down). **Your own units walk through yours**; a sector you take keeps
  its old owner's mines, and your units strike them until its people are yours.
- **Striking one.** A unit marching into another country's mines strikes one with chance
  **N/(N+35)** — a third of that for an engineer — and loses **10 + roll(20)** points, scaled by its
  vulnerability (half for an engineer), and stops there. An engineer sweeps as it goes: twice its
  shell load in tries, at half its attack each; swept mines are shells again.
- **Defence.** An attack on a sector meets its owner's land mines: each adds **2%** to the
  defence, counting at most 20 (+40%); half as many count against an attack with an engineer.
- `census` lists your land mines, and the sector panel shows them.

## What is not here

The original also damaged goods moved by hand across another country's land mines. Moving goods
here has no damage on the way to carry that, so it is left out.
