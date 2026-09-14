# M5 plan — units, combat, missions

Everything parked while the economy, ships and rail were built on 2026-09-08 and
2026-09-09, gathered at Richard's request ("put all the next things into the M5
plan"). Each item is a GitHub issue on the **M5 — Units, combat, missions**
milestone; the issue carries the detail, this page carries the order.

## Order of work

Status as of **2026-09-13**. Done items keep their place so the reasoning for
the order stays readable.

1. **Ships phase 3 — warships and submarines** (#68) — **done.** It landed in
   the three slices Richard asked for on 2026-09-09. First **detection** (#75):
   radar and lookouts find enemy ships, and contacts age and go stale. Then
   **combat**: `fire` resolves now with return fire (Richard 2026-09-13); at the
   update every hostile battery fires simultaneously; damage is carried on the
   hull's efficiency, so a harbour repairs it; sinking; salvage by the victor;
   forts and harbours defend the coast. Last **missions**: patrol, search,
   escort, blockade and interdiction of trains, each coming home for supplies. A
   warship fights when there is a war or a peacetime shot to answer, never on
   sight, so the fishing fleet never blunders into a war. Relations (#137) came
   first, because "at war" is what makes engagement automatic.
2. **Fuel** (#65) — **done.** Petrol per hex, tanks, refuelling in harbour and
   from tankers.
3. **Crews** (#66) — **done.** People aboard, signed on in harbour; crewless
   hulls hold.
4. **Map memory** (#64) — **done.** Seen sectors stay on the chart, dimmed as
   they go stale.
5. **Ships phase 2** (#67) — **done.** Lanes that feed thresholds, the `supply`
   mission, same-update arrival, tankers that fill their own tanks, a logbook
   per ship.
6. **Rail follow-ups** (#70) — **done.** Standing rail lanes, depots as
   distribution links, train markers.
7. **Planes and land units** (#71) — **next in this plan.** The rest of the
   units table. Land combat, capture of sectors and air missions join the
   simultaneous resolution that sea combat uses (step 10a).
8. **Unrest** (#72) — open. Loyalty and revolt from the happiness requirement,
   so happiness has teeth.
9. **Immediate navigation** (#69) — **done.** A per-ship mobility pool, and haste
   costs more.
10. **Agents and the terminal client** (#73) — **done** for ships, lanes,
    deliver orders, macros and bridges. They do not yet know `supply`, `fire` or
    the warship missions.

Outside the M5 list but on the same road: **trade between nations** (#141),
which wants #67's lanes and harbours.

## Already done that M5 builds on

- Ships phases 2 and 3 (#67, #68): supply and logbooks; gunnery, sinking, coastal
  guns, blockades and warship missions. `engine.combat.Gunnery` is the one
  place a salvo's arithmetic lives, shared by the `fire` command and `CombatStep`.
- Relations (#137), news (#121), seabed mining (#112).
- Ships phase 1 (#56): the class table, harbours as shipyards, sailing, fishing
  (with the roaming mission), lanes, luxury craft, tech-scaled speed, sight (#62).
- Deliver orders (#45), macros (#47), mass commands (#38), sector history (#49).
- Rail as a cheaper road with automatic bridges and tunnels (#60), trains paying
  the sending depot (#59).
- Sanctuaries shown with their owner (#54); people never shipped into full
  sectors (#48).

## Rules of the road for M5

- Every number in a units table is config (`config/schema.yaml`, `units.yaml`),
  tagged KNOWN / GUESS / NEW as elsewhere.
- Order-independence stays: no unit result may depend on scan order. Ships already
  run in id order with the harbour stock as the only contended resource; combat
  will need simultaneous resolution.
- Every feature ships with an engine test, a sector- or ship-history line, and a
  console verb for agents before a panel for people.
