# Decisions

One entry per decision taken during the spike, with the evidence. The arc42
rewrite waits for the device gate, so these are implementation decisions, not
architecture decisions.

## D-01 — Modality block matrix instead of per-item confidence

**Status:** adopted, tested.

The plan wrote the cold-candidate kernel as

```
S_ij = c_i c_j <u_i, u_j> + beta,   c = 1 for audio, rho for text
```

That form also scales the diagonal: a text candidate gets `S_ii = rho^2`, so its
self-similarity is 0.49 against 1.0 for a played track. The DPP then prefers
music the user has already heard, which is the opposite of the Explore mix.
Adding `beta` does not repair it; a constant offset flattens contrast without
restoring the diagonal.

Adopted instead:

```
M_ij = 1 (same modality) | rho (cross modality)
S    = M (*) (U U^T) + beta
L_ij = q_i q_j S_ij + lambda I
```

`M` is a two-block correlation matrix, PSD for `|rho| <= 1`; the Schur product
with the Gram matrix is PSD; the diagonal scaling is a congruence. The diagonal
stays at 1, so quality decides between cold and warm candidates and only
cross-modal pairs are damped.

Unrolled: audio-audio `G_ij`, audio-text `rho * G_ij`, text-text `G_ij`.
`beta` defaults to 0.

Evidence: `spike/kernel/test_kernel.py`, including 200 random seeds of mixed
modality with a Cholesky that never fails, and a Cauchy-Schwarz check.

## D-02 — Tap at index 0 of the processor chain

**Status:** implemented, device gate pending.

The tap is inserted before `VolumeNormalizationAudioProcessor`, the equalizer
and `SilenceDetectorAudioProcessor`, so analysis sees the decoder output rather
than processed audio. It is a strict pass-through and only copies bytes into a
pre-allocated ring buffer; downmix and resampling run on a worker thread.

Known limitation: audio offload bypasses the processor chain entirely. With
offload enabled the tap produces no windows. The spike measures this rather than
silently disabling offload; product behaviour is a decision for v1.1.

Evidence: `app/.../playback/audio/{AudioTap,TapAudioProcessor,PcmRingBuffer,SincResampler}.kt`
and the JVM unit tests for the ring, the resampler and buffer reuse
(12 tests, `BUILD SUCCESSFUL`, APK assembles). Device underrun measurement is
still blocked on hardware.

## D-03 — Fork pinned as a plain tree, not a submodule

**Status:** adopted.

`app/` is a shallow clone of `MetrolistGroup/Metrolist` at `e6fea3b` with its
own `.git` moved to `/tmp/opencode/radiostar/app-git-shallow`. The parent
repository owns the source, which keeps the monorepo model from the original
brief and avoids an unconfigured gitlink. Upstream history stays available and
`git pull` from upstream is still possible by pointing a remote at it later.

## D-04 — Application id `link.nzrs.radiostar`, `.dev` on debug

**Status:** adopted.

`defaultConfig.applicationId` is now `link.nzrs.radiostar`; the debug build type
applies `applicationIdSuffix = ".dev"`. Release keeps the bare id.
`namespace` stays `com.metrolist.music` so the fork's R/BuildConfig package and
every import stay untouched; only the install identity changes.

Components that depend on the id resolve it at build time: the manifest uses
`${applicationId}.FileProvider`, and the broadcast actions are literals shared
between sender and receiver, so they keep working.

## D-05 — First device run lost 15% of the audio

**Status:** fixed, pending re-measurement.

First Pixel 9 Pro XL run emitted one window every 11.78 s for a 10 s window:
15% of the audio never reached the accumulator. `droppedBlocks` reported 38 per
window, but that counter increments per dropped *write call* (~8 KB each), not
per 64 KB block, so the log overstated the loss by an order of magnitude.

Cause: a 3-block usable ring (1 s) plus a worker whose resampling pass took
roughly 1.5 s, so the ring sat full and dropped continuously.

Fixes, all in this order:

- Ring 8 blocks (2.3 s usable) instead of 4 (1 s).
- Worker wakeup 10 ms instead of 25 ms.
- Worker priority `THREAD_PRIORITY_BACKGROUND` instead of `MIN_PRIORITY`; the
  audio thread must not starve it.
- Resampler inner loop no longer calls `coerceIn` per tap; edges are handled by
  three separate loops, so the body has no bounds checks.
- Emission reuses a buffer instead of allocating 1.9 MB per window.
- Counter reports `droppedBytes`, not write calls.

Acceptance: window cadence 10.0 s ± 0.1 s, `dropped=0B`, `underruns=0`.

Measured after the fixes on a Pixel 9 Pro XL, 44.1 kHz stereo, 45 s of
playback: intervals 10.019 / 10.061 / 10.000 s, dropped 0 B, underruns 0.
Reproduce with `spike/tap/measure.sh 45`.

## D-06 — CLAP HTSAT-tiny INT8 on device

**Status:** latency passes, size marginal.

Measured on a Pixel 9 Pro XL, one 10 s window, 10 runs after 3 warmups:

| config | p50 | p95 |
|---|---|---|
| 2 threads, CPU EP | 1200 ms | 1326 ms |
| 4 threads, CPU EP | 1601 ms | 2111 ms |
| 6 threads, CPU EP | 2002 ms | 2038 ms |
| 4 threads, XNNPACK | 345 ms | 1178 ms |

- Export: 121.8 MB fp32, ONNX-vs-torch cosine 1.000000, max abs error 6.6e-7.
- INT8 dynamic (`reduce_range=False`): 34.7 MB.
- Session load: 2.4–4.8 s. PSS delta ~20–28 MB.
- Host x86, 2 threads: 89 ms p50.

Reading:

- A clean 20-run sample at 4 threads + XNNPACK: p50 1103 ms, p95 1545 ms.
  An earlier 10-run sample reported p50 345 ms; it did not reproduce, so treat
  ~1.1–1.5 s as the real figure and ignore the 345 ms.
- On the actual target class — Galaxy A52s 5G, Snapdragon 778G, Android 14 —
  the same APK and config measured **p50 172 ms, p95 194 ms**. The Pixel 9 Pro
  numbers were contention/thermal, not representative. On mid-range hardware a
  10 s window embeds in ~0.2 s, roughly 50× real time.
- One 20-run sample contained a 666 s outlier: the activity had been backgrounded
  with the screen off and the process was frozen mid-inference. Embedding must
  run inside the already-foreground `MusicService` process, not a background
  activity, or the system will suspend it.
- XNNPACK plus 4 threads is still the best configuration, but the margin over
  2 threads CPU is small (1103 vs 1200 ms p50). More CPU threads without XNNPACK
  is strictly worse (1200 → 1601 → 2002 ms): the SoC has few big cores.
- 34.7 MB is above the 15 MB GO line. Ship `arm64-v8a` only and treat the
  remaining size as the next optimisation target.
- Load is 0.5–0.8 s warm and 2.4–4.8 s cold. Create the session once, off the
  main thread, and keep it alive; close it on teardown or it leaks ~25 MB per
  launch.
- At ~1.2 s per 10 s window, three windows per track cost ~3.6 s of CPU over a
  3–5 minute track: roughly a 1.5–2% duty cycle. The asynchronous design holds
  even at 3× slower mid-range hardware.

## D-08 — Candidate pool and text bridge

**Status:** both verified end to end.

- `YouTube.next(WatchEndpoint(videoId))` returns 51 items for a seed track in
  ~1.2 s. That is the candidate pool; no crawling needed for a first slate.
- CLAP text embeddings for those candidates (laptop, `server/text_embedder.py`)
  cluster correctly: three tracks by the same artist score 0.81–0.92, different
  regional-Mexican artists 0.80–0.84, unrelated pairs 0.38–0.61.
- The text tower stays on the laptop: ~125M parameters is not affordable next
  to the 31M audio tower on a 6 GB phone.

Consequence: a cold candidate now has a vector comparable to a played track's
audio vector, which is the missing half of the DPP kernel.

## D-09 — First end-to-end slate

**Status:** works, log-only (not injected into the queue).

Pipeline as measured on the Galaxy A52s:

1. `YouTube.next(WatchEndpoint)` -> 51 candidates in ~1 s.
2. 48 usable candidates sent to the laptop `/embed` -> 512-d text vectors.
   Cold: ~14 s for 48 items. Cached: ~4 ms per request.
3. Quality `q_i = cosine(audio embedding of the playing track, text vector_i)`.
4. `DppKernel.selectSlate` with one modality, `k = 10`.
5. Slate logged; playback untouched: 0 B dropped, 0 underruns, 9.97-10.01 s
   window cadence throughout.

Observed slate for a regional-Mexican seed: Grupo Frontera, Peso Pluma,
Grupo Firme, Fuerza Regida, Calibre 50, Lenin Ramirez (the seed artist),
Los Dareyes. Coherent, but it keeps the seed's artist; a diversity rule is
still missing.

Two fixes found on the way:

- Android blocks cleartext HTTP by default. `usesCleartextTraffic=true` is set
  in the **debug** manifest only.
- A candidate with a blank artist or title made the server answer 400 and the
  client failed silently. The client now filters blank metadata and logs
  `embed http N body=...` on any non-200.

Server URL lives in the device global setting `radiostar_embed_server`
(`adb shell settings put global radiostar_embed_server http://<laptop>:8787`),
so the laptop IP can change without a rebuild.

## D-10 — Session state, mixes and queue injection

**Status:** engine complete and verified on device.

- `SessionState`: wall-clock exponential moving average, 10-minute half-life,
  unit length. Plays attract, skips repel. A long gap replaces the state.
- `MixBuilder`: quality is `cosine(session, candidate vector)`; the DPP removes
  redundancy; an artist cap stops one act from filling the slate.
  - DISCOVER: excludes history and the seed artist, max 2 per artist.
  - JAMS: keeps history and the seed artist, max 3 per artist.
  - EXPLORE: flattens quality by 50% so diversity dominates, max 1 per artist.
- Queue injection under `radiostar_queue_mode=append`. The previous slate's
  unplayed tail is removed before the new one is inserted, so the queue stays
  bounded: measured 30 → 32 after a refresh, not 30 → 40.
- 25 JVM tests: `DppKernelTest` 8, `MixBuilderTest` 9, `SessionStateTest` 8.

Bug found on device: a `MediaItem` with only `mediaId` and metadata makes
`DefaultMediaSourceFactory.createMediaSource` throw an NPE. The item needs
`setUri(mediaId)` and `setCustomCacheKey(mediaId)` like the project's
`MediaMetadata.toMediaItem()` helper.

Still open: JAMS and EXPLORE are only unit-tested, not exercised on device;
order is score-descending until tempo/key DSP exists; there is no UI and no DJ.

## D-11 — The engine ships in the release build

**Status:** reversed the earlier debug-only placement.

A release without the CLAP model cannot recommend, so `ClapEmbedderImpl` and
`clap_tiny_int8.onnx` moved from `src/debug` to `src/main`, ONNX Runtime became
an `implementation` dependency, and the debug-only guard on the analysis tap
was removed. ONNX Runtime classes are kept explicitly in `proguard-rules.pro`
because the Java bridge is reached from JNI.

The ABI filter is now `arm64-v8a` only, the reference device. One APK path is
kept (`app-foss-{debug,release}.apk`) so the measurement scripts do not change.

Measured with `assembleFossDebug` and `assembleFossRelease` (R8, unsigned):

```
debug   111.1 MB
release  83.4 MB
```

Release is smaller because R8 shrinks the app code; both packages carry the same
34.7 MB model and 32.1 MB ONNX runtime for arm64.

The release APK contains `assets/clap_tiny_int8.onnx` (34.7 MB) and
`lib/arm64-v8a/libonnxruntime.so` (32.1 MB). `classes.dex` still contains the
`ai.onnxruntime` bridge after R8, so the keep rules work. Release runtime was
not exercised on the device because the APK is unsigned.

Release is still unsigned because the repository has no release keystore; it
was not installed on the device. The signed debug build is the one exercised
below.

## D-12 — Mix UI, DJ voice, energy ordering, ListenBrainz

**Status:** superseded by D-14 for the mix model. The DJ and ListenBrainz
parts remain; the mix selector and automatic queue injection were removed.

- **UI.** `Settings → RadioStar` (`settings/radiostar`) keeps the DJ switch,
  the embedding server URL and the ListenBrainz import.
- The old `radiostar_mix` / `radiostar_energy` / `radiostar_harmony` globals
  and the queue-injection path are gone: the app never changes what the user
  chose to play.
- **DJ.** Platform TTS (no bundled voice). It announces the next track 8 s
  before the transition, ducks the player to 22% while speaking and restores
  the effective volume afterwards. A 60 s partial wake lock keeps the utterance
  alive. Measured with the screen off (`mScreenState=OFF`, Dozing):
  `announce → duck on 0.22 → utterance start → utterance done → duck off 1.00`,
  while the tap kept emitting 10.000 s windows with 0 B dropped. Barge-in is
  avoided by keying the announcement to the `current>next` media-id pair.
- **Queue across restarts.** Slate items carry
  `MediaMetadata.extras["link.nzrs.radiostar.slate"] = true`, and their ids are
  also stored in the DataStore key `radiostarInjected`. Before inserting a new
  slate the service removes every unplayed item with the flag or with an id in
  the persisted set. The set matters because the persisted queue rebuilds
  `MediaItem`s from the app metadata and drops the extras. Measured across a
  forced process restart: queue 78 → 77 after the second slate, not +7 or +8.
  The first slate after the change could not clean the legacy items queued
  before either marker existed; those fade out as they play.
- **Energy.** The tap already emits per-window RMS; the service stores it per
  media id. When the toggle is on, `EnergyOrderer` fills the slate slots that
  have a profile from quiet to loud. Candidates never played keep their score
  position, so the first session is unchanged. Tempo and key remain out.
- **ListenBrainz.** `ListenHistory` parses the export shape
  `Title,Artist,Album,YouTube Video ID` (RFC 4180 quotes, BOM, missing header),
  reduces artists to the primary credit and stores `title|artist` keys.
  Discover and Explore exclude those tracks; Jams keeps them. The file picker
  uses `OpenDocument`; no listening data leaves the phone.
  `scripts/listenbrainz_export.py` downloads listens and writes the same CSV,
  with 10 unit tests of its own.

Fixes found during device verification:

- Cold 48-item embedding can exceed the old 30 s client timeout on a loaded
  laptop. The client now waits 120 s.
- A slate `MediaItem` needs `setTag(app MediaMetadata)`. The queue screen and
  the persisted queue read `MediaItem.metadata` from the tag, so an item with
  only `mediaMetadata` made `Queue.kt:663` throw an NPE when the screen opened.
  After the fix, launching the app with the old queue persisted produced no
  fatal exception.
- The slate log printed `mixKind()` (the global fallback) instead of the kind
  actually used, so an Explore slate appeared as Jams. It now prints the kind
  stored by `buildSlate`; the qualities already proved the real behaviour.

## D-13 — On-device tempo and key

**Status:** implemented, unit-tested and measured on the A52s.

`TempoKeyEstimator` works on the same 10 s mono window the tap already emits,
with no native library:

- **Tempo.** 1024-point FFTs with a 512 hop feed a spectral-flux onset
  envelope. The envelope is mean-subtracted, normalised, and autocorrelated
  over 70–180 BPM with a 120 BPM log-normal prior and a `acf(lag) +
  0.5 acf(2 lag)` comb term so the beat does not collapse to half time. The
  winning lag is refined parabolically.
- **Key.** 16384-point FFTs give 2.9 Hz bins, enough to put a 110 Hz bass note
  on A rather than the neighbouring semitone. Magnitudes map to a chroma with
  an 18% bass boost, then Pearson correlation against the Krumhansl-Kessler
  major and minor profiles for all 12 roots. The margin between the best and
  second profile is the confidence.
- **Cost.** 294 ms for the first window of a track on the A52s. Tempo and key
  are stable inside a track, so the service analyses the first window and
  reuses the result; later windows only update RMS. A missing key is retried
  on the next window.

`AudioOrderer.harmonicPath` chains the part of the slate that already has a
profile: starting from the playing track, it greedily picks the smallest
tempo/key/RMS transition cost and puts the resulting path back into the slots
those tracks already occupied. Candidates never played keep their score
position. Camelot numbers give the key distance: same key 0, relative or
adjacent 1, otherwise 2.

The service persists each analysed profile to DataStore
(`radiostarProfiles`). The playlist generator reads them and orders the slots
that already have a profile as a harmonic path; if only RMS is known it uses
the energy arc. Unknown candidates keep their DPP position.

Tests: 26 cases across `MusicalKeyTest`, `TempoKeyEstimatorTest` and
`AudioOrdererTest`; the recommendation suite is now 95 tests.

**Limit.** Candidate tracks are text-only, so their tempo and key are unknown
until they play. Harmony therefore matters most after a few listens; it cannot
rank a cold candidate by BPM.

## D-14 — Playlists, not playback modes

**Status:** corrected after the user's device test. The earlier shelf treated
Discover/Jams/Explore as modes that injected tracks into the queue; that was
wrong and was removed.

The app now has **playlists only**. It never adds, removes or reorders what the
user chose to play.

Six generated playlists:

| Playlist | Window | Size | Refresh |
| --- | --- | --- | --- |
| Daily Discover / Jam / Explore | last 7 days | 30 | once per calendar day |
| Weekly Discover / Jam / Explore | last 30 days | 30 | once per ISO week |

DJ Cherry is **not** a playlist. It is a DJ session (D-15).

- Ids are fixed (`LP_RS_DAILY_DISCOVER`, `LP_RS_WEEKLY_JAMS`, ...), so a
  refresh replaces the same rows.
- `PlaylistSchedule` is the only scheduling rule. A stamp in DataStore stores
  the date/week that was successfully generated. Repeated app starts, Home
  recreations and background checks do not regenerate anything; a failed run
  leaves the stamp unchanged so the next attempt retries.
- History comes from the local `event` table (most played in the window, with
  an all-time fallback). Up to 5/8 anchor songs feed YouTube radio; the pool
  plus the familiar history tracks is text-embedded by the laptop server.
  The taste centroid is the normalised mean of the history vectors.
- Discover and Explore exclude everything in the listening history. Jam keeps
  the familiar tracks and their neighbours.
- The existing DPP/artist cap builds each slate. Tracks are stored as normal
  local playlists with song rows and artist maps, so the native playlist screen
  plays them, and Library lists them because they are bookmarked.
- Home shows **RadioStar playlists** cards; opening one navigates to
  `local_playlist/{id}`. There is no play-on-shelf action and no queue edit.
- `RadioStarPlaylistSpecsTest` covers the six ids, names, horizons and sizes;
  `PlaylistScheduleTest` covers the once-per-period rule. The recommendation
  suite is now 95 tests.

Device evidence (A52s, laptop server on LAN):

```
RadioStarPlaylists: wrote Daily Discover tracks=30
RadioStarPlaylists: wrote Daily Jam tracks=30
RadioStarPlaylists: wrote Daily Explore tracks=30
RadioStarPlaylists: wrote Weekly Discover tracks=30
RadioStarPlaylists: wrote Weekly Jam tracks=30
RadioStarPlaylists: wrote Weekly Explore tracks=30
RadioStarPlaylists: generated daily=true weekly=true
RadioStarPlaylists: cards=6
```

SQLite check on `song.db`:

```
LP_RS_DAILY_DISCOVER  30
LP_RS_DAILY_JAMS      30
LP_RS_DAILY_EXPLORE   30
LP_RS_WEEKLY_*        30 each
```

**Limits.** Without the embedding server no new playlists are generated; the
existing ones stay. A fresh install without listens shows the empty shelf. The
visual shelf and opening a generated playlist still need the user's device
test; the database rows, artist maps and generation path are verified.

## D-15 — DJ Cherry is a session, not a playlist

**Status:** implemented MVP in `feature/dj-cherry`; candidate pool reused from generated playlists.

- DJ Cherry left the generated-playlist set and the playlist shelf.
- Home gets a smaller DJ block, separate from the playlists: a fruit emoji,
  the selected persona name, and the line *Let the DJ play the beat*.
- Tapping it opens the DJ panel, not a playlist. The panel has a radial wheel
  with three dimensions and three stops each (Never / Sometimes / Frequently):
  familiar tracks, tracks far from the taste, and tracks that look like the
  taste. The same wheel selects the persona.
- Personas are fruit characters with a short name, emoji and personality:
  Cherry (default), Lemon, Berry, Banana, Strawberry, Coco. The persona changes
  the TTS pitch/rate and the wording, not the recommendation math.
- Starting the DJ is explicit. Only then does the app build an on-demand queue
  and talk between tracks. Nothing changes the queue outside a DJ session.

## D-16 — Scrobbler-first listening history

**Status:** read/import implemented for all three services; local JSONL store; outbound ListenBrainz planned.

- The local event table is not enough: a user who listens elsewhere would get
  generic recommendations. The app must import listens from Last.fm,
  ListenBrainz or a self-hosted compatible service, store them on the phone,
  and merge them with local events before generating playlists.
- Import is paged, resumable and idempotent: a checkpoint per service, a
  unique fingerprint per listen, validation of required fields, bounded
  retries with backoff, and no stamp advance on failure.
- Before downloading, the importer queries the service's total listen count
  (Last.fm `user.getInfo`, ListenBrainz/self-hosted `listen-count`). The UI
  shows `fetched / total` with a progress bar. A run only marks the checkpoint
  complete when the fetched count reaches the total (with a small tolerance);
  an old capped run is detected by comparing the local per-service count with
  the total and is rebuilt from zero.
- Sync frequency is configurable: startup, 15 min, 1 h, 6 h, daily, weekly or
  off. A completed import immediately requests playlist regeneration through
  `RadioStarRefreshBus`; daily/weekly stamps are bypassed for that explicit
  refresh.
- Outbound scrobbling stays as backup. The app both sends its own listens and
  downloads the service's history. Last.fm outbound is the existing path;
  ListenBrainz/self-hosted submission remains planned.
- Without a linked scrobbler the playlists use a generic seed and are marked
  as such; the empty or misleading history is never presented as personal.

## D-17 — RadioStar brand

**Status:** implemented.

- App label is **RadioStar** (`RadioStar Debug` for the dev build); package ids
  stay `link.nzrs.radiostar` / `.dev` so debug installs do not collide with the
  upstream Metrolist package.
- Adaptive icon foreground and monochrome mask are a minimal strawberry with
  headphones. Source SVG: `docs/assets/radiostar-icon.svg`.
- Settings → RadioStar includes **Credits and licences**; the full list lives in
  `docs/CREDITS.md` (Metrolist, LAION CLAP, ONNX Runtime, Media3, DPP paper,
  key profiles). Rejected MERT/Essentia licences are recorded.

## Open
- CLAP HTSAT-tiny checkpoint licence (HF weights are Apache-2.0; the Zenodo
  checkpoint is a separate artefact).
- Text-tower placement: on-device next to the audio tower is not affordable,
  so cold-candidate text vectors depend on the laptop.
