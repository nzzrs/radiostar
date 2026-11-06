# CLAP HTSAT-tiny export

**Status:** scripts written, not executed. This container has no `torch`, so the
recipe is unverified past syntax. Expect to adjust `laion_clap` attribute names
on the first real run.

## Why this model

- Audio tower only, HTSAT-tiny, single 10 s / 48 kHz mono window.
- Not `laion/clap-htsat-fused`: that is the 154M fused variant and wants four
  10 s crops.
- The checkpoint must be the music/AudioSet/ESC one
  (`music_audioset_epoch_15_esc_90.14.pt`), not the generic environment model.

Licence: the HF `laion/clap-htsat-fused` weights are Apache-2.0. The Zenodo
checkpoint is a **separate artefact**; read its own licence file before shipping
and record the result in `docs/decisions.md`.

## Environment

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install torch torchaudio laion-clap onnx onnxsim onnxruntime numpy
```

## Export

```bash
python spike/clap/export_clap.py \
    --checkpoint /path/to/music_audioset_epoch_15_esc_90.14.pt \
    --out spike/clap/out
```

Produces:

- `out/clap_tiny.onnx` — `mel [1,1,1001,64] -> embedding [1,512]`, L2 normalised.
- `out/clap_mel.onnx` — optional waveform → mel front-end, so device and
  reference share arithmetic.

`export_clap.py` prints the fp32 ONNX-vs-torch cosine. It must be ≥ 0.999.
If it is not, the export is wrong; quantisation cannot fix it.

## Quantize

```bash
python spike/clap/quantize.py \
    --input spike/clap/out/clap_tiny.onnx \
    --output spike/clap/out/clap_tiny_int8.onnx \
    --parity-dir spike/clap/out/parity
```

`reduce_range=False`. The flag only exists for x86 AVX2 `VPMADDUBSW` saturation;
ARM64 does not need it and it costs accuracy. Keep a separate x86 artefact if
the laptop path needs one.

Parity gate: cosine(fp32, int8) mean ≥ 0.98, min ≥ 0.95 on 100 real clips.

## Prove the embedding is useful, not just stable

Cheap sanity check before any integration:

- same track, two windows: cosine ≥ 0.80
- different tracks: median cosine ≤ 0.60

If those fail, the space is not discriminative and the kernel is built on sand.

## Integration notes

- `AudioTap` now delivers windows at the **native** rate (44.1 kHz on the test
  device) because resampling inside the drain loop cost 3-4% of the audio.
  Resample in the inference worker with `SincResampler` before feeding the
  model; `SincResamplerTest` covers it.
- One inference at a time, on a single worker thread. `setIntraOpNumThreads(2)`,
  `setInterOpNumThreads(1)`, `ALL_OPT`.
- Load the model by path (mmap), not as a `byte[]`.
- Do not enable NNAPI first. HTS-AT is windowed attention plus conv; NNAPI
  partitions badly. Measure the CPU EP, then compare XNNPACK.

## Android gate (from the approved plan)

| Metric | GO |
|---|---|
| Embed, 10 s window, p95 | ≤ 2.0 s |
| Model file INT8 | ≤ 15 MB |
| Incremental RSS | ≤ 180 MB |
| Model load | ≤ 1.5 s |
| Audio underruns during inference | 0 |
| Battery, %/h extra | ≤ 3 |
| 60 min soak RSS growth | ≤ 5 MB |

Three windows per track × 2 s is a 3.4% duty cycle. That is the number that
decides whether this ships on-device.
