# Your first turn

## Getting in

Empire has no passwords, and no sign-up step either. **Taking a country is the
sign-up.**

Go to the join page, pick a game with a seat going, name your country and give
an email address. A link arrives; clicking it signs you in and takes the seat.
You are then signed in for three months, and signing in later means asking for
another link.

Between claiming and clicking, the seat is **held** for half an hour — nobody
else can take it, and the game will not start without you. If the link goes
unclicked the seat goes back on offer and you can simply claim it again. The
hold exists so that a game cannot be started by someone who typed an address
they do not own.

## Joining a game

The games page lists every game. Each one shows its size, which update it is on,
how long until the next one, and the seats in it.

Seats start out numbered — `emp1`, `emp2` and so on — and **taking one means
naming it**. The name is what every other player sees on the map.

You can change it later with **Rename** on the game's card. The change is
immediate for everyone, including on the parts of the map they remember rather
than currently see, because names are looked up when a map is drawn rather than
stored with it. Two countries in the same game cannot share a name.

A game does not begin the moment it is created. It waits in **setup** until
either every seat is taken or the deity starts it — so if you are the last one
in, your claim is what starts the game. Before that you can look at your country
and read this guide, but no commands are accepted and no updates run. Joining
early gains you nothing, which is the point.

## What you own

Two sectors: your **capital** and one neighbouring **sanctuary**. Between them
they start with about 1,100 civilians, 110 military, 2,000 food, 200 light
construction materials, and your treasury starts at $25,000.

Both sectors begin in **sanctuary**. While you are in sanctuary:

- nobody can see you and nobody can touch you,
- and you cannot act outside your two sectors either.

Sanctuary is a shelter, not a strategy. You leave it with `break`, and you
cannot go back.

## The map

`map` prints what you can see. Coordinates are **relative to your capital**,
which is always `0,0` — so `2,0` is two sectors east of your capital and
`-1,-1` is one to the north-west. Everyone's map is centred on their own
capital, so the coordinates you use are not the coordinates your neighbour
uses for the same hex.

You see your own sectors, the ones next to them, and whatever your radar and
ships have spotted. Everything else is dark, and stays dark until you go and
look.

## What to actually do

The loop is: **designate sectors, feed the people, and get the goods to where
they are useful.**

A sensible opening:

1. **`census`** — one line per sector you own, so you can see what you have.
2. **Designate something that feeds you.** `des 1,0 agribusiness` turns a
   sector into farmland. Food is the constraint that bites first: people eat
   every update, and a sector that runs out starts losing them.
3. **Designate something that builds.** A `mine` makes iron ore from a sector
   with minerals; `light_manufacturing` turns iron into light construction
   materials, which is what almost everything else is built from.
4. **`break`** when you are ready to exist. Until you do, nothing outside your
   two sectors is yours to touch.
5. **`expl 0,0 1,0 100`** — send 100 civilians into an empty neighbouring
   sector to claim it. Territory comes from walking onto it.

Then wait for the update, and read what happened.

## Reading what happened

After each update, every sector you own can tell you what it did: what it ate,
what it built, what it made, what it sent and what it received, in the order
those things happened. That per-sector history is the single most useful thing
in the game when something is not working. A sector that is not producing will
usually say why.

## The three things that surprise people

**Efficiency.** A freshly designated sector is at 0% and produces *nothing*
until it reaches 60%. It builds itself up using the labour of the people
standing on it: half a sector's work goes to construction, at one efficiency
point per work unit. A sector with 1,000 civilians goes from 0% to 100% in a
single update; one with 100 civilians gains 30 points, so it is three updates
before it produces anything at all. Designating a sector is the beginning of a
project, and how fast it finishes depends entirely on how many people are
standing there.

**Work comes from people.** Everything a sector does — building itself up,
producing goods — is paid for in work, and work is civilians, uncompensated
workers and military standing in that sector. An empty sector does nothing, no
matter how well designated.

**BTUs.** Every command costs you Bureaucratic Time Units, which refill from
the civilians in your capital. You cannot do an unlimited number of things per
update, and a capital you have neglected gives you fewer of them. Most commands
cost 1; exploring and building rail or ships cost 2.

## Next

Read **[The update](the-update.html)**. Everything above is setup; the update
is where the game actually happens.
