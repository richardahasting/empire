# Empire

A browser-based re-implementation of the 1980s multiplayer strategy game
*Empire* (Peter Langston; Wolfpack/Chainsaw lineage). Sector economy, deep
supply chains, timed world updates, fog of war, and diplomacy between real
people — with a web UI that finally shows the goods moving.

Live at <https://hastingtx.org/empire/> (holding page until M1).

## Read first

- [`docs/build-prompt.md`](docs/build-prompt.md) — the spec.
- [`config/schema.yaml`](config/schema.yaml) — every knob, commented, with
  `KNOWN` / `GUESS` / `NEW` provenance on each mechanic.
- [`docs/update-sequence.md`](docs/update-sequence.md) — the twelve-step
  deterministic update, plan-then-apply.
- [`docs/module-layout.md`](docs/module-layout.md) — Maven modules and the
  dependency rule.

## Status

**M0 in progress** (issue #3): engine, config loader, world generator, the
deterministic update with plan-then-apply flows, ScriptedAgent, and the headless
simulation harness. 23 tests including the six-direction and 60° rotation
symmetry proofs, a jqwik conservation property, a golden state hash, and the
calibration probes. No persistence, HTTP, or UI yet.

```bash
mvn -q test                                   # everything
make sim PRESET=teaching UPDATES=60 COUNTRIES=3 SEED=9   # headless run -> sim-out/
```

All numeric rates in `config/schema.yaml` are placeholders; the harness exists
to calibrate them.

## Stack

Java 21 / Spring Boot 3 / PostgreSQL / React + TypeScript + Vite + Tailwind.
