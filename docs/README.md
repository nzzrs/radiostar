# RadioStar documentation

Start with the root [README](../README.md) for what the app does and how to
build it. Use this index when you need the design or reasoning behind it.

| Document | Purpose |
| --- | --- |
| [`../server/README.md`](../server/README.md) | Server setup, commands, configuration, API and operations. |
| [`architecture.md`](architecture.md) | arc42 architecture: goals, constraints, building blocks, runtime, deployment, quality, risks. |
| [`c4-model.md`](c4-model.md) | C4 Level 1–4 views, sequences, deployment diagram and arc42 mapping. |
| [`decisions.md`](decisions.md) | Numbered architecture decisions D-01 onwards and evidence. |
| [`CREDITS.md`](CREDITS.md) | Upstream app, models, libraries, artwork and rejected licences. |
| [`spike.md`](spike.md) | Closed research spike and what moved into the app. |

`docs/idea/` is local-only and gitignored. It contains the original brief,
reports and the generated icon source, which are not shipped documentation.

## Reading order

1. [README](../README.md) for the product summary and quick start.
2. [`architecture.md`](architecture.md) §1–§4 for scope, constraints and strategy.
3. [`c4-model.md`](c4-model.md) Level 1–2 for the system and container view.
4. [`c4-model.md`](c4-model.md) Level 3–4 for components and critical sequences.
5. [`decisions.md`](decisions.md) for why a choice was made.
6. [`architecture.md`](architecture.md) §10–§11 for quality scenarios and known debt.

## Server operations

Day-to-day commands live in [`../server/README.md`](../server/README.md#commands).
For a quick check after a change:

```sh
python -m server doctor
python -m pytest server/tests -q
```
