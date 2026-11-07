# Credits and licences

RadioStar is a fork of **Metrolist** (GPL-3.0). The upstream project, its
contributors and its notices are preserved. RadioStar's own code and
documentation are released under GPL-3.0.

## Upstream application

- Metrolist — YouTube Music client for Android, GPL-3.0.
  https://github.com/mostafaalagamy/Metrolist

## Models and research

- **LAION CLAP** (`laion/clap-htsat-fused`, audio and text towers),
  Apache-2.0. Used for 512-d audio/text embeddings.
- **ONNX Runtime**, MIT. Executes the INT8 CLAP graph on device.
- **Fast Greedy MAP Inference for Determinantal Point Processes**,
  Chen, Zhang and Zhou (2018). Basis for `DppKernel`.
- **Krumhansl–Kessler key profiles** (1982), used by the on-device key
  estimator. The implementation is original Kotlin, not copied code.
- **Camelot wheel / harmonic mixing** notation, used for key distance.
- **Media3 / ExoPlayer**, Apache-2.0. Playback, PCM tap and TTS ducking.
- **Room, DataStore, Hilt, Kotlin Coroutines**, Apache-2.0.

## Data and services

- **YouTube Music / InnerTube** metadata and radio candidates. RadioStar does
  not redistribute audio.
- **Last.fm** and **ListenBrainz** APIs for optional scrobble import/export.
  Their respective terms apply.
- **Tailscale / MagicDNS** is only the user's private network transport; it is
  not bundled or required.

## Rejected for licensing

- **MERT** — CC-BY-NC weights.
- **Essentia** — AGPL-3.0 and non-commercial model variants.
- **CLaMP 3** — not selected for the shipping build.

## Artwork

- RadioStar app icon: AI-generated strawberry-with-headphones artwork,
  provided by the RadioStar project. The source image is
  `docs/idea/raw icon idea.jpeg`; the cropped, background-cleaned master is
  `docs/assets/radiostar-icon.png`. The launcher mipmaps and adaptive
  foreground/monochrome layers are derived from it.
- Fruit emoji in the DJ persona picker are rendered by the platform emoji font.

## Trademarks

RadioStar is not affiliated with YouTube, Google, Last.fm, ListenBrainz or
Metrolist. Names are used only to describe compatibility.
