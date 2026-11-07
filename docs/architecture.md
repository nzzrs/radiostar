# RadioStar — Architecture (arc42)

Status: living document, 2026-11-06. It describes the implemented system and
separates what is still planned. Planned items are marked **[planned]**.

## 1. Introduction and Goals

RadioStar is a privacy-first music recommendation engine inside a fork of
Metrolist, a YouTube Music client. Audio is analysed only while it is already
playing; no library of audio is stored or uploaded. Recommendations are
delivered as playlists, never by changing what the user chose to play.

### 1.1 Requirements overview

- Stream from YouTube Music.
- Tap the decoded PCM of the current stream, embed a 10 s window, discard it.
- Generate **playlists**, not playback modes:
  - Daily Discover, Daily Jam, Daily Explore.
  - Weekly Discover, Weekly Jam, Weekly Explore.
  - DJ Cherry is a separate on-demand DJ session.
- A daily list is generated once per calendar day; a weekly list once per ISO
  week. They never regenerate continuously.
- Import listening history from an external scrobbler so recommendations are
  not blind to listening outside the app.
- Optionally use a laptop for the CLAP text tower; the phone must still work
  for playback and local analysis.
- FOSS-clean licences, explicit credits, no telemetry leaving the device by
  default.

### 1.2 Quality goals

| Priority | Goal | Scenario |
| --- | --- | --- |
| 1 | User control | The app never inserts, removes or reorders queue items unless the user starts a DJ session. |
| 2 | Privacy | Audio never leaves the phone; only title/artist metadata reaches the user's own embedding server. Scrobble tokens stay on the phone. |
| 3 | Honest scheduling | Opening Home ten times in one day generates the daily lists zero times after the first. |
| 4 | Recovery | A failed scrobbler import keeps its checkpoint unchanged and retries later without duplicates. |
| 5 | Playback budget | Audio analysis does not cause dropped PCM windows or underruns. |
| 6 | Explainability | Each playlist has a horizon and a known history window; the UI does not pretend a generic list is personal. |

### 1.3 Stakeholders

- Single user / owner of the phone and laptop.
- Maintainer of the fork.
- Upstream Metrolist (GPL-3.0) and the model/dataset authors.
- Optional scrobbler services: Last.fm, ListenBrainz, self-hosted
  ListenBrainz-compatible servers.

## 2. Constraints

### 2.1 Technical

- Android 8+ (minSdk 26), reference device Samsung Galaxy A52s, arm64-v8a.
- Media3/ExoPlayer playback; audio is tapped through an `AudioProcessor`.
- CLAP HTSAT-tiny INT8 on ONNX Runtime, 10 s / 48 kHz mono windows.
- Pure-Kotlin tempo/key DSP; no native MIR library.
- The text tower does not fit acceptably on the phone; it runs on a laptop
  HTTP service.
- Room database for library/history/playlists; DataStore for preferences.
- No release keystore in the repository; release APKs are unsigned.

### 2.2 Organizational and legal

- GPL-3.0 fork; upstream notices are preserved.
- CLAP weights Apache-2.0; MERT (CC-BY-NC) and Essentia (AGPL + NC models) are
  rejected.
- `docs/idea/` is private and gitignored.
- All code, docs and commits are in English.

### 2.3 Conventions

- Feature branches from `dev`; merge to `dev` after tests; `main` only after the
  user tests `dev`.
- Tests before behaviour changes; no assertion deletion to make a build pass.
- No secrets in the repository.

## 3. Context and Scope

### 3.1 Business context

```text
                 ┌─────────────────────────────┐
                 │        Android phone         │
  user ─────────▶│ UI · playlists · DJ · player │
                 │ local history · analysis     │
                 └───────┬─────────────┬───────┘
                         │ HTTPS       │ HTTP (LAN/Tailscale)
                         ▼             ▼
                 ┌───────────────┐  ┌────────────────────┐
                 │ YouTube Music │  │ laptop text tower  │
                 └───────────────┘  │ /health /embed     │
                                    └────────────────────┘
                         │
                         │ HTTPS, optional
                         ▼
          ┌─────────────────────────────────────┐
          │ Last.fm / ListenBrainz / self-hosted │
          │ scrobbler: download + backup upload  │
          └─────────────────────────────────────┘
```

### 3.2 External interfaces

- **YouTube Music / InnerTube**: radio candidates, stream URLs, metadata.
- **Embedding server** `POST /embed`, `GET /health`, port 8787. Bound to
  `0.0.0.0`, reachable over LAN or Tailscale; no authentication (LAN-only
  developer service).
- **Scrobblers**:
  - Last.fm Web API: `user.getRecentTracks`, `track.scrobble`,
    `track.updateNowPlaying`.
  - ListenBrainz: `GET /1/user/{user}/listens`, `POST /1/submit-listens`.
  - Self-hosted: any ListenBrainz-compatible base URL and token.
- **Android TTS**: platform engine, user-selected voice; no bundled model.

### 3.3 Scope

In scope: playlist generation, local history, scrobbler import/export, DJ
session, playback analysis, settings.

Out of scope: a public multi-user service, collaborative filtering, training
models, storing or redistributing audio, a web player.

## 4. Solution Strategy

1. **Local-first.** History, preferences and generated playlists live on the
   phone. The laptop is an optional compute extension for text embeddings.
2. **Playlists are artifacts, not modes.** Generation writes ordinary local
   playlists with fixed ids. Playback only starts when the user opens one.
3. **Scheduled, idempotent generation.** `PlaylistSchedule` compares the
   current date/week with success stamps. A run replaces the same six rows and
   never advances the stamp on failure.
4. **Scrobbler-first history.** Before generating, merge local events with
   imported listens. Without an external source, use a generic seed and label
   the result as generic instead of pretending it is personal.
5. **Frozen models, no training.** CLAP audio/text embeddings, DPP diversity,
   session EMA, tempo/key DSP, artist caps.
6. **Explicit DJ.** The DJ builds and plays a queue only after an explicit
   start action. The rest of the app never mutates the queue.
7. **Cheap by design.** Text embeddings are cached; DPP is O(N k²); analysis
   runs once per 10 s window and tempo/key once per track.
8. **Fail visibly, recover quietly.** Import and generation use checkpoints,
   bounded retries and logs; UI shows empty/generic states rather than fake
   data.

## 5. Building Block View

### 5.1 Level 1

```text
app/
├── ui/            Compose screens, Home shelf, DJ panel
├── viewmodels/    HomeViewModel, settings
├── recommend/     DPP, MixBuilder, session, tempo/key, playlist repository
├── playback/      MediaService, AudioTap, DjAnnouncer, queue
├── db/            Room entities/DAOs (songs, events, playlists)
├── scrobble/      Last.fm API, scrobbler import adapters
server/            Python text tower (/health, /embed, SQLite cache)
scripts/           ListenBrainz CSV export tool
spike/             archived research evidence
```

### 5.2 Recommendation core (`recommend/`)

| Component | Responsibility |
| --- | --- |
| `DppKernel` | PSD-safe DPP kernel and fast greedy Cholesky selection. |
| `MixBuilder` | Quality, DPP, artist cap and per-kind parameters. |
| `SessionState` | Time-decayed play/skip vector, 10 min half-life. |
| `TempoKeyEstimator` | FFT tempo and chroma key, pure Kotlin. |
| `MusicalKey` / `AudioOrderer` | Camelot distance and harmonic path. |
| `EmbeddingClient` | Chunked `/embed` HTTP client with 180 s timeout. |
| `RadioStarPlaylistRepository` | Six-playlist generation, persistence, schedules. |
| `PlaylistSchedule` | Once-per-day / once-per-ISO-week rule. |
| `ListenHistory` | Normalised `title|primary artist` keys. |

### 5.3 Playback and analysis (`playback/`)

- `MusicService`: MediaSessionService; owns the player, tap, embedder, DJ
  announcer, settings mirror.
- `AudioTap`: ring buffer, mono downmix, 10 s windows, RMS; never writes audio.
- `ClapEmbedderImpl`: ONNX Runtime, resampling, one window in flight.
- `DjAnnouncer`: platform TTS, 8 s lookahead, 22% duck, partial wake lock.

Analysis pipeline:

```text
decoded PCM → TapAudioProcessor → ring buffer → 10 s window
  → CLAP INT8 → 512-d audio embedding → SessionState
  → tempo/key/RMS → in-memory profile + DataStore
  → (playlist generator uses profiles later)
```

### 5.4 Persistence

- Room:
  - `song`, `artist`, `song_artist_map` — library and generated songs.
  - `event` — local plays (songId, timestamp, playTime).
  - `playlist`, `playlist_song_map` — normal playlists, including generated.
  - `radiostar_listens.jsonl` — **implemented** append-only external listens,
    deduplicated by a normalised fingerprint; malformed lines are skipped.
  - `scrobblerCheckpoints` in DataStore — **implemented** per-service cursor;
    a Room table is a future migration if the file grows large.
- DataStore:
  - `RadioStarDjKey`, `RadioStarServerKey`, `RadioStarHistoryKey`,
    `RadioStarProfilesKey`, playlist stamps, DJ settings.

### 5.5 External text tower (`server/`)

- Loads CLAP text tower once (~20 s).
- `GET /health` → `{"ok": true, "model": "clap-htsat-tiny"}`.
- `POST /embed` → 512-d unit vectors, SQLite cache, one lock around the model.
- Standard library only; binds `0.0.0.0:8787`.

## 6. Runtime View

### 6.1 Daily and weekly playlists

```text
app start / Home init
  → PlaylistSchedule.plan(today, dailyStamp, weeklyStamp)
  → none due? load cards from Room and stop
  → due? build history from local events + imported listens
  → anchors → YouTube radio → embed pool (chunks of 48)
  → centroid → MixBuilder per spec → write fixed playlist ids
  → write daily/weekly stamps
  → publish cards
```

Properties:

- Daily and weekly are independent; a new day does not rebuild weekly lists.
- Stamps are written only after the group succeeds.
- The same six ids are replaced, never duplicated.
- No periodic loop. If the app stays open across midnight, the next Home
  recreation or process start checks again; an optional WorkManager job may be
  added for exact midnight refresh **[planned]**.
- Fixed ids: `LP_RS_DAILY_DISCOVER`, `LP_RS_DAILY_JAMS`,
  `LP_RS_DAILY_EXPLORE`, `LP_RS_WEEKLY_DISCOVER`, `LP_RS_WEEKLY_JAMS`,
  `LP_RS_WEEKLY_EXPLORE`.

### 6.2 Playlist generation details

- History windows: 7 days daily, 30 days weekly; all-time fallback.
- Discover/Explore: exclude all heard ids and normalised keys.
- Jam: familiar tracks plus radio neighbours.
- DPP with artist caps; generated songs/artists are inserted into Room so the
  native playlist screen and playback work.
- Ordering: harmony (BPM/key/RMS) for tracks with persisted profiles, else
  energy arc, else DPP score. Unknown candidates keep their slot.

### 6.3 Scrobbler import

```text
on app start / manual import
  → read checkpoint(service, user)
  → adapter.fetchPage(checkpoint, page, limit)
  → validate schema (title, artist, listened_at)
  → normalise + fingerprint(service, user, artist, title, listened_at)
  → transaction: insert ignore imported_listen rows
  → advance checkpoint only after the transaction commits
  → repeat until page empty / limit reached
  → failure: backoff, keep checkpoint, surface status
```

Adapters:

| Service | Read | Write / backup |
| --- | --- | --- |
| Last.fm | `user.getRecentTracks` (extended, paged) — implemented | `track.scrobble`, `track.updateNowPlaying` — existing |
| ListenBrainz | `/1/user/{user}/listens` (`max_ts`, count) — implemented | `/1/submit-listens` — **[planned]** |
| Self-hosted | same ListenBrainz-compatible endpoints on a base URL — implemented | same — **[planned]** |

The RadioStar settings screen selects the service and shows only the fields
that service needs. Before downloading, it asks the service for the total
listen count; the progress bar and the `fetched / total` label use that number.
A run that ends short is marked incomplete, keeps its checkpoint and retries;
if the local per-service count is below the service total, the importer
rebuilds from zero (repairing old capped runs). Incremental runs read from the
newest page and stop at the saved timestamp. The last result is persisted. The
sync frequency is startup, 15 min, 1 h, 6 h, daily, weekly or off. A completed
import requests an immediate playlist refresh.

Resilience rules:

- Dedupe by fingerprint, not by array position.
- Page size 100–200; max page guard; HTTP 429/5xx → exponential backoff.
- Timeouts, bounded retries, no partial checkpoint advance.
- Validate timestamps (reject future beyond skew, keep epoch ordering).
- Imported rows are immutable; a changed source row is a new fingerprint only
  when its timestamp differs.
- Tokens never logged. They live in device DataStore; wrapping them with the
  Android Keystore is a hardening step **[planned]**.

Merging with recommendations:

- `imported_listen` rows contribute title/artist text to the taste centroid and
  to the heard-key exclusion set.
- Local `event` rows remain the source of playable history for Jam anchors.
- If there are no local playable anchors, resolve the top imported tracks to
  YouTube ids via search (bounded, cached) **[planned]**.
- Local and imported duplicates collapse by normalised title/artist and
  timestamp proximity.

Generic cold start:

- No scrobbler and little local history → a generic seed (charts/genres) and a
  visible "generic" label. Never present generic lists as personal.
- Once imported listens exist, the same pipeline becomes personal.

Outbound backup:

- Existing `ScrobbleManager` sends Last.fm scrobbles with a delay threshold.
- ListenBrainz/self-hosted submission and an offline retry queue are
  **[planned]**. The app both sends its listens and downloads history.

### 6.4 DJ Cherry

Entry point: a compact Home block separate from playlists:

```text
🍒  DJ Cherry
    Let the DJ play the beat
```

Tapping the card starts the session; the gear opens the DJ settings panel.

Panel controls:

- A radial wheel with three dimensions:
  - familiar tracks: Never / Sometimes / Frequently
  - tracks far from the taste: Never / Sometimes / Frequently
  - tracks that look like the taste: Never / Sometimes / Frequently
- The same wheel selects the persona; a segmented control mirrors every wheel
  stop for accessibility.
- Personas: 🍒 Cherry (default), 🍋 Lemon, 🫐 Berry, 🍌 Banana, 🍓 Strawberry,
  🥥 Coco. Each has a short personality, TTS pitch/rate and wording.
- The card starts the session; the gear opens settings. Save persists the
  wheel/persona and restarts the session. The AI provider (OpenRouter, OpenAI,
  Gemini or custom) is chosen in Settings → AI, not in the DJ panel.
- The DJ builds an on-demand queue from the generated playlists: Jam is
  familiar, Discover is likely, Explore is far. The weights pick buckets;
  duplicates and the 20-track cap are enforced. It plays and speaks; ducking
  and screen-off behaviour reuse `DjAnnouncer`.
- A full-screen abstract sphere view is shown while the voice is speaking, then
  the song view returns.
- Optional AI commentary sends the persona plus previous/next track to the
  configured OpenAI-compatible provider. If the call fails, the template
  announcement is spoken. The API key can be stored AES/GCM-encrypted in the
  Android Keystore.
- Persona affects voice and copy only, never the candidate math.
- Leaving the session returns to normal playback; background generation and
  playlists never touch the queue.

### 6.5 DJ voice (current)

- Platform TTS, 8 s lookahead before the next track.
- Duck player to 22%, partial wake lock, restore effective volume.
- Screen-off verified: announce → duck → utterance → done → restore.

## 7. Deployment View

- **Phone**: one APK, arm64-v8a, debug `link.nzrs.radiostar.dev`, release
  `link.nzrs.radiostar` (unsigned in this repository).
- **Laptop**: Python 3.12 venv, `server/app.py`, port 8787. LAN or Tailscale;
  MagicDNS `nzen` / `nzen.tail81a589.ts.net` in the reference setup.
- **Scrobblers**: internet HTTPS from the phone; no laptop involvement.
- **No cloud dependency**: without the laptop, playback still works; new
  playlists using text embeddings are skipped until it is reachable. Local
  audio analysis and the DJ continue.

## 8. Cross-cutting Concepts

### 8.1 Privacy and security

- Audio buffers are ephemeral; never logged, written or uploaded.
- Metadata sent to the embedding server is title/artist only.
- Scrobbler tokens are device-local; redact them from logs and crash reports.
- Cleartext HTTP is allowed only for the user's own embedding server; the
  network security config permits LAN/Tailscale.
- No analytics SDK; telemetry is local unless the user opts into an external
  service.

### 8.2 Error handling

- Generation failure leaves old playlists and old stamps.
- Import failure leaves the checkpoint and retries with backoff.
- Embedding server failure returns null; the playlist generation aborts
  cleanly instead of writing partial lists.
- Playback analysis failures are logged and never block audio.

### 8.3 Performance budgets

| Operation | Budget / measured |
| --- | --- |
| CLAP embedding | p50 172 ms, p95 194 ms per 10 s window (A52s) |
| Tempo/key | ~294 ms, once per track |
| DPP | milliseconds for 30–160 candidates |
| Text embedding | ~1.1 s cold/item, ~4 ms cached (laptop) |
| Playlist refresh | background; daily/weekly only |

### 8.4 i18n, accessibility, licensing

- UI strings in English and Spanish; code/docs English.
- DJ wheel has a non-radial segmented fallback; cards have content labels.
- Every model/library credit is kept in the README/decisions; GPL-3.0 fork.

## 9. Architecture Decisions

See `docs/decisions.md`:

- D-01..D-10: audio tap, CLAP model, DPP kernel, identity, text tower, engine.
- D-11: engine ships in release; arm64 only; APK sizes.
- D-12: DJ voice and ListenBrainz CSV import.
- D-13: on-device tempo/key and audio ordering.
- D-14: playlists, not playback modes; six scheduled playlists.
- D-15: DJ Cherry is a session, not a playlist.
- D-16: scrobbler-first history.

## 10. Quality Requirements

| Scenario | Expected |
| --- | --- |
| Open Home five times on the same day | Only the first check generates daily lists; the rest load the existing rows. |
| Wait until next day | Daily lists regenerate once; weekly stay. |
| Kill the app during generation | Stamp not advanced; next launch retries; old playlists remain. |
| Import 5,000 Last.fm scrobbles | Paged, deduped, resumable; no duplicate rows; UI remains responsive. |
| Lose network mid-import | Checkpoint stays at last committed page; retry resumes. |
| Play a playlist | Native queue; automatic generation does not modify it. |
| Start DJ | Explicit queue; TTS speaks; no effect outside the session. |
| No scrobbler, no history | Empty shelf or clearly marked generic lists. |
| Embedding server offline | Playback unaffected; old playlists remain; new generation skipped. |

## 11. Risks and Technical Debt

- **Cold-start blindness** until scrobbler import exists; current local-only
  history is a product risk.
- Text-only candidates cannot have BPM/key until played; harmonic ordering is
  partial.
- The laptop dependency limits playlist generation away from home.
- Generated playlist rows can accumulate orphan songs; a cleanup policy is
  pending.
- Last.fm API keys are build-time; self-hosted auth is not implemented.
- Release builds are unsigned; no store distribution.
- DJ personas and the panel are implemented; the candidate pool is the
  generated playlists, not a dedicated DJ candidate engine yet.
- ListenBrainz/self-hosted outbound submission is not implemented; Last.fm
  outbound scrobbling is the existing path.
- Imported listens are stored as JSONL, not yet a Room table; very large
  histories will need the migration.
- Testing is mostly JVM + on-device logs; no Compose UI tests yet.

## 12. Glossary

- **Listen**: one play event with title, artist and timestamp.
- **Scrobble**: a listen submitted to an external service.
- **Playlist horizon**: daily or weekly generation window.
- **Taste centroid**: normalised mean of history text embeddings.
- **DPP**: determinantal point process used for a diverse slate.
- **Camelot distance**: key compatibility on the DJ wheel.
- **DJ session**: user-started, queue-owning, talking playback mode.
- **Generic recommendations**: fallback lists not derived from personal
  history, labelled as such.
