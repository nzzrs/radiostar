# Spike: Media3 tap + CLAP-tiny INT8

**Archived.** The spike closed on 2026-11-06 and its results moved to
`app/`. This directory stays as reproducible evidence; see
[`docs/spike.md`](../docs/spike.md) for the summary and
[`docs/decisions.md`](../docs/decisions.md) for the rationale.

```
spike/
├── kernel/      PSD-safe DPP kernel for mixed audio/text candidates   [done, tested]
├── identity/    videoId -> canonical recording resolver               [done, tested]
├── tap/         Media3 PCM tap + measure.sh / soak.sh                  [device-verified]
└── clap/        export + quantization                       [done, parity 1.000000]
```

Run the tests from the repository root:

```bash
python3 -m pytest spike -q
```

Expected: `55 passed`.

## What is verified

### `spike/kernel`

`build_kernel` + `fast_greedy_dpp`, with these guarantees proved by test:

- `L` is positive definite for every mix of audio and text candidates, so
  Cholesky never sees a non-positive pivot.
- Cauchy-Schwarz holds on every pair (`S_ij^2 <= S_ii S_jj`), which is the
  condition that keeps each Schur complement non-negative.
- 200 random seeds, mixed modalities, no failure.
- Duplicate candidates are not selected twice.
- The incremental greedy matches an exhaustive determinant greedy on a small
  instance, so the fast path is the same algorithm as the slow path.
- Quality normalisation never underflows (the `exp(-distance)` failure mode).

**Formula change from the approved plan.** The plan wrote
`S_ij = c_i c_j <u_i,u_j> + beta` with `c = rho` for text items. That form also
scales the *diagonal* to `rho^2`, so every cold candidate carries a
self-similarity of 0.49 against 1.0 for a played track, and the DPP
systematically prefers music the user has already heard. The implementation
uses the block form instead:

```
M_ij = 1      same modality
     = rho    cross modality
S    = M (*) (U U^T)
L_ij = q_i q_j S_ij  + lambda I
```

`M` is a two-block correlation matrix, PSD for `|rho| <= 1`, and the Schur
product with the Gram matrix stays PSD. The diagonal stays at 1, so cold and
warm candidates compete on quality alone, and only cross-modal pairs are
damped. `beta` is kept as an optional non-negative offset, default 0: a
constant added to every entry flattens contrast and does not improve
conditioning.

Unrolled:

```
(a) audio vs audio : S_ij = G_ij
(b) audio vs text  : S_ij = rho * G_ij
(c) text  vs text  : S_ij = G_ij
```

### `spike/identity`

- Nine spellings of the same recording collapse to one `recording_id`.
- Live, remix, acoustic, sped up, instrumental and cover stay separate.
- A radio edit stays separate from the album cut (duration filter, ±3 s).
- Diacritics, `feat.` credits, leading track numbers, `&` vs `and`, and a
  `"Artist - Title"` upload all resolve to the same key.
- `"Live and Let Die"` is not mistaken for a live version: variant markers
  only count inside brackets or in a non-leading dash segment.
- Resolution is idempotent per `videoId`.

## What is blocked

### `spike/tap` — device gate still pending

Implemented in `app/app/src/main/kotlin/com/metrolist/music/playback/audio/`:

| File | Role |
|---|---|
| `PcmRingBuffer.kt` | SPSC block ring, drops instead of blocking |
| `SincResampler.kt` | 44.1/48 kHz → 48 kHz, Hann-windowed sinc |
| `TapAudioProcessor.kt` | pass-through `BaseAudioProcessor`, copies only |
| `AudioTap.kt` | worker, mono downmix, 10 s windows, stats |

Wired at index 0 of the `DefaultAudioProcessorChain` in `MusicService`, before
normalization, EQ and silence detection. Disabled by default: the tap only
copies when `AudioTap.enabled` is set, so playback behaviour is unchanged.

Build evidence:

```
./gradlew :app:testFossDebugUnitTest --tests 'com.metrolist.music.playback.audio.*' \
          :app:assembleFossDebug
BUILD SUCCESSFUL — 12 tests, 0 failures — app-foss-debug.apk 46 MB
```

Device evidence (Pixel 9 Pro XL, 44.1 kHz stereo, 45 s of playback, twice):

```
intervals: 10.032 10.040  9.994
intervals: 10.036 10.050 10.004
dropped 0 B, underruns 0
```

Re-run with `spike/tap/measure.sh 45`; 30-minute soak with
`spike/tap/soak.sh 30`.

Compilation and the JVM tests do not prove the device gate. Zero `AudioTrack`
underruns over 30 minutes, `queueInput` p99 ≤ 0.3 ms and no allocation on the
audio thread still need the soak and a profiler run.

### `spike/clap` — needs a torch environment

This machine has no `torch`, so export and quantization cannot run here. The
recipe is in the approved plan (§2.2–2.3). Remaining work is one script and one
parity report on a machine with `torch`, `onnx`, `onnxruntime` and
`onnxsim`.

## Open questions carried into the next step

1. Does `DefaultAudioSink.Builder.setAudioProcessors` exist on the pinned
   Media3 version, or is `setAudioProcessorChain` required?
2. Does the `laion_clap` HTSAT-tiny checkpoint declare Apache-2.0, or only the
   HF `clap-htsat-fused` weights do? Licence must be confirmed before shipping.
3. Text-tower placement: on-device is not affordable next to the audio tower,
   so cold-candidate text vectors depend on the laptop being reachable. Confirm
   that degradation is acceptable.
