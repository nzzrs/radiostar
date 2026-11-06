# Research spike — closed 2026-11-06

The spike answered one question: can a phone recommend from a continuous
YouTube Music stream without storing audio, and can it stay within an ordinary
playback budget?

Yes. The engine now lives in `app/`; `spike/` is kept as the reproducible
evidence behind the decisions, not as a second implementation.

## What the spike proved

| Area | Result |
| --- | --- |
| PCM tap | 10.000 s windows, 0 B dropped, 0 underruns, 30 min soak PASS |
| CLAP export | FP32 parity 1.000000, INT8 34.7 MB |
| Embedding cost | A52s p50 172 ms / p95 194 ms per 10 s window |
| DPP kernel | PSD-safe, duplicate-free, golden case shared with Kotlin |
| Recording identity | videoId to canonical recording, 36 tests |
| Text tower | LAN service, SQLite cache, cold ~1.1 s/item, cached ~4 ms |
| End to end | radio candidates to text vectors to DPP slate to playback queue |

## What moved to production

- `spike/kernel` -> `app/.../recommend/DppKernel.kt` (same golden selection).
- `spike/tap` -> `app/.../playback/audio/AudioTap.kt`.
- `spike/clap` -> `app/.../recommend/ClapEmbedderImpl.kt` plus the INT8 asset,
  now shipped in release instead of debug only.
- `spike/identity` -> the resolver design; the app uses canonical video ids.
- `server/` stays as the laptop-side text tower.

## What stayed research

- Python reference implementations and their tests, used to check the Kotlin
  ports.
- Recorded measurements and the soak scripts.
- The rejected options and why: MERT (CC-BY-NC), Essentia (AGPL + NC models),
  the `c_i c_j` DPP kernel (double-penalised cold candidates).

## Closed questions

- Tempo and key needed real DSP; they are now implemented in
  `app/.../recommend/TempoKeyEstimator.kt`.
- The mix surface moved from a debug setting to the RadioStar settings screen
  and the Home shelves.

See `docs/decisions.md` for the numbered decisions and the measured evidence.
