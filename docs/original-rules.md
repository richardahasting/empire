# Original rules, reconciled

Source: Wolfpack Empire server, `gefla/empserver` master on GitHub (2026-09-08):
`src/lib/global/{sect,product,item,infra}.config`, `constants.c`,
`src/lib/update/{sect,produce,human,populace,mobility,distribute}.c`,
`src/lib/common/{move,btu,res_pop}.c`, `info/{designate,Infrastructure}.t`.
Everything below marked **matched** is now in `config/schema.yaml` with the
file cited beside the value. **Deviation** means ours differs on purpose or
for lack of the mechanic; decide those in the morning.

## Building a sector (designation)

| Rule | Original | Ours before | Now |
|---|---|---|---|
| Materials to build efficiency | none for almost every type (`l_b`/`h_b` = 0); fortress 100 hcm per 100%; (big city 100 lcm + 200 hcm, off) | 1 lcm per point everywhere, hcm for some | **matched**: `build: { cash: 1 }`; fortress `{ hcm: 1, cash: 5 }` |
| Cash to build | $1 per point (`cost` 100); fortress $5; terrain types free | $0 | **matched** |
| Work to build | half the sector's available work may build; 1 point per work unit (`bwork` 100 hundredths); work per update = people × ETUs / 100 | 25 work per point, capped 24 points/update | **matched**: `work_per_point: 100`, `build_work_share: 0.5`, cap lifted. 1000 civilians finish a sector in one update; 100 civilians gain 30. |
| Capital upkeep | $1 per ETU (`maint`) | none | **matched** |
| Redesignation | efficiency is torn **down** at 4× build speed by the workers, then rebuilt; nothing is instant | instant reset (configurable keep fraction) | **deviation** — tear-down phase not modelled. Decide: implement tear-down, or keep the keep-fraction knob. |
| Production gate | nothing below 60 % efficiency | produced at any efficiency | **matched**: `production_min_efficiency: 60` |

## Production (per person-ETU, at 100 % efficiency, full resource)

`output = min(work, materials, resource) × level_p_e × peffic/100`, work = people × ETU / 100, one unit per `bwork`.
`level_p_e = (level − nlmin) / (level − nlmin + nllag)`, zero below `nlmin` (**matched** as curve type `wolfpack`).

| Product | Original | Ours before | Now |
|---|---|---|---|
| food | 0.09 × fertility, tech curve (−10, lag 10 → 0.5 at tech 0) | 0.009 | **matched** (was 10× too low) |
| iron / dust / oil / rad | 0.01 × resource; oil and rad tech-gated; rad $2 | 0.003 / 0.001 / 0.003 / 0.0005 | **matched** |
| lcm (1 iron) / hcm (2 iron) | 0.01 / 0.005, tech curve | 0.003 / 0.0015 | **matched** |
| petrol | 1 oil → 10 petrol, 0.1, $1, tech ≥ 20 | 1 oil → 1 pet | **matched** |
| shells / guns | 0.00333 (2 lcm + 1 hcm, $3) / 0.000625 (1 oil + 5 lcm + 10 hcm, $30), tech ≥ 20 | rough | **matched** |
| bars | 0.002 (5 dust, $10) | 0.0002 | **matched** |
| tech / research | 0.000625 (1 dust + 5 oil + 10 lcm), $300 / $90, education ≥ 5 | 0.0003 | **matched** |
| education / happiness | 0.01 per lcm, $9 | 0.0002 / 0.0004 | **matched** (school = original library; university is ours) |
| Resource depletion | gold −20, oil −10, uranium −35 per 100 units produced | none | **deviation** — not modelled yet |
| Enlistment | at ≥ 60 %: ETU × (10 + mil) × 0.05 per update, ≤ civ/2 − mil, $3 each | flat 0.001 per work | **deviation** — special formula not modelled; $3 now charged |

## Mobility and moving goods

| Rule | Original | Ours before | Now |
|---|---|---|---|
| Sector mobility per update | flat ETUs × 1.0 = 60, cap 127, regardless of efficiency | 60 × efficiency (floor 25 %) | **matched** |
| Cost to enter a sector | 0.4 per weight unit at 0 %, 0.2 at 100 % (linear); wilderness 0.4 flat; mountain 2.4 → 1.2; highway 0.4 → 0 | 0.8 → 0.32 | **matched** (forest 0.6, swamp 0.8 are ours) |
| Roads | cost × (1 − 0.009 × road): a tenth at road 100 | diminishing to a quarter | **matched** |
| Building roads / rail | road: 2 lcm + 2 hcm + 1 mobility + $2 per point; rail: 1 lcm + 1 hcm + 1 mobility + $1 per point (infra.config) | road 1.2 lcm + $8 + work; rail 2 hcm + 1 lcm + $30 | **matched** (rail lands in M4 with these numbers) |
| Weight | lbs / packing: civilians pack 10 in any ≥ 60 % sector; goods pack 10 leaving a warehouse or harbor; bars 5 (warehouse) / 4 (bank); uw 2 (warehouse) | flat weight; warehouse stored 10× | **matched** — packing added; warehouse storage back to 9999 |
| Who pays a hand move | the **source** sector, and the whole route must be affordable or nothing moves | every sector entered pays; partial moves | **deviation** (spec's rule). Ours charges the sectors entered and moves what fits. |
| Reach of a hand move | unlimited (mobility is the limit) | 6 sectors | **matched** (limit set to 100) |
| Distribution cost | path cost / packing × lbs / **10** (IMPORT/EXPORT_BONUS), paid by the **sender** (centre for imports, sector for exports), capped by its mobility | full price, paid by sectors entered | **half matched**: the ÷10 is in (`mobility_bonus: 10`); who pays is still the spec's rule — **deviation** |
| Distribution reach | none | 3 sectors (+ tech, + roads) | **deviation** (spec's rule) |

## Population

| Rule | Original | Ours before | Now |
|---|---|---|---|
| Eating | 0.0005 per person per ETU | same | matched |
| Starvation | victims = people − food/(ETU × 0.0005), at most half; uw first, then civ, then mil | up to 25 %, proportional across classes | **matched** |
| Births | 0.005 × ETU × adults; ≤ food / (2 × 0.006); ≤ maxpop; none in a starving sector | food per birth 0.0006 (10× too cheap) | **matched** (`food_per_birth: 0.006`, reserve factor 2) |
| Population cap | 1000 flat per designated sector (mountain 100); RES_POP option scales by research | 1000 × efficiency | **matched** (flat; `{ type: res_pop }` available) |
| Subsistence | modern Wolfpack: "emergency rations" = work/2 × 0.0013, capped by ETU × fertility × 0.0012 — a few food, effectively nothing. Richard's memory of ~300 civilians living off the land is from older Empire. | Richard's rule: 300 × fertility/100 forage | **kept Richard's rule** (deliberate) |
| Plague | option NO_PLAGUE off by default; own model | ours | deviation, low priority |

## Money, BTUs, levels

| Rule | Original | Ours before | Now |
|---|---|---|---|
| Taxes | civ 0.0083333, uw 0.0017777 per ETU | ≈ same | matched |
| Military pay | 0.0833333 per soldier per ETU (10× a civilian's tax) | 0.0083 | **matched** |
| Start cash | 25000 | 5000 | **matched** |
| BTUs | ETU × civilians (≤ 1000) × efficiency-in-percent × 0.0012, cap 640: a full capital refills 640 every update | 24 per update | **matched** |
| Level decline | 1 % per 96 ETUs of the level (proportional), not tech | flat 0.0002 per ETU | **deviation** — M4, decide then |
| Happiness/education consumption | hap_cons / edu_cons 600000 with averaging windows | own | deviation — M4 |
| Bank interest | bankint 0.25 "dt × bars" | 0.0025 per bar per ETU | unresolved — read `nat.c` in M4 |

## Notes for the morning

1. **Who pays for movement.** The original charges the *sender* (and distribution at a tenth of the price). The spec chose "every sector entered pays" for symmetry. With the ÷10 now in, distribution should stop choking, but a zero-mobility new sector still cannot receive a hand move. Options: keep the spec's rule, switch to sender-pays, or sender-pays with the entered sector charged a small share.
2. **Hand moves are all-or-nothing in the original**; ours moves what fits. Keeping ours unless you object.
3. **Redesignation tear-down** (4× build speed) versus our keep-fraction knob.
4. **Resource depletion** for gold, oil, uranium.
5. **Enlistment** formula.
6. **Distribution reach limit** is the spec's invention; the original had none.
7. **Forest, university, hospital, defense plant** have no original numbers; theirs are guesses.
8. Subsistence: yours (300 × fertility) stays; the modern server's version is negligible.
