# M5 plan — units, combat, missions

Everything parked while the economy, ships and rail were built on 2026-09-08 and
2026-09-09, gathered at Richard's request ("put all the next things into the M5
plan"). Each item is a GitHub issue on the **M5 — Units, combat, missions**
milestone; the issue carries the detail, this page carries the order.

## Order of work

1. **Ships phase 3 — warships and submarines** (#68). Detection, combat,
   missions, harbour defence. The core of the milestone; everything below feeds it.
2. **Fuel** (#65). Petrol per hex, tanks, refuelling in harbour and from tankers.
   Gives refineries a purpose and makes tankers matter. Changes the whole economy,
   so it lands early, before balance work.
3. **Crews** (#66). People aboard, loaded in harbour; crewless hulls hold.
4. **Map memory** (#64). Seen sectors stay on the chart, dimmed as they go stale —
   the natural partner to ships lifting the fog (#62) and to detection.
5. **Ships phase 2** (#67). Lanes as distribution links so islands are supplied
   without orders; same-update load/unload; tankers for refinery supply; per-ship
   history.
6. **Rail follow-ups** (#70). Standing rail lanes, depots as distribution links,
   train markers on the map.
7. **Planes and land units** (#71). The rest of the units table.
8. **Unrest** (#72). Loyalty and revolt from the happiness requirement, so
   happiness has teeth.
9. **Immediate navigation** (#69). A per-ship mobility pool if update-time
   movement proves too slow at the keyboard. Decide after playing phase 3.
10. **Agents and the terminal client** (#73). Teach them ships, lanes, deliver
    orders, macros and bridges so tournaments exercise the sea and the rails.

## Already done that M5 builds on

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
