# RadioStar — Architecture (arc42)

Status: living document, 2026-11-07. It describes the implemented system and
separates what is still planned. Planned items are marked **[planned]**.

The C4 context, container, component, code, sequence and deployment views are
in [`c4-model.md`](c4-model.md).

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
  - AI DJ is an optional, on-demand DJ session; DJ Cherry is one persona.
- A daily list is generated once per 06:00-to-06:00 local day; a weekly list
  once per ISO week. They never regenerate continuously.
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
                         │ HTTPS       │ HTTPS (Cloudflare/Tailscale)
                         ▼             ▼
                 ┌───────────────┐  ┌────────────────────┐
                 │ YouTube Music │  │ laptop text tower  │
                 └───────────────┘  │ /health /auth /embed│
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
- **Embedding server** `POST /embed`, `GET /health`, `/auth/*`, port 8787.
  Binds `127.0.0.1` by default and runs behind Cloudflare Tunnel or another
  HTTPS reverse proxy. `/embed` requires a device Bearer token; access tokens
  last 15 minutes and refresh tokens rotate.
- **Sync server** `/sync/pull`, `/sync/push`, `/sync/status`, `/sync` on the
  same base URL and the same device Bearer token. Config values and
  like/dislike reactions are stored per user with a monotonic sequence; secret
  config values are encrypted on the phone before upload.
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
   06:00-based playlist day and ISO week with success stamps. A run replaces
   the same six rows and never advances the stamp on failure.
4. **Imported-first history.** Before generating, use imported listens as the
   primary anchors and taste signal. Local Metrolist events are the fallback
   when no imported history exists.
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

The C4 views for this section are in [`c4-model.md`](c4-model.md):
Level 2 container and Level 3 component diagrams. The C4 Level 1 context is
also mirrored in Section 3.1 below.

### 5.1 Building block level 1 — repository layout

```text
app/
├── ui/            Compose screens, Home shelf, DJ panel
├── viewmodels/    HomeViewModel, settings
├── recommend/     DPP, MixBuilder, session, tempo/key, playlist repository
├── playback/      MusicService, TapAudioProcessor, DjAnnouncer, queue
├── db/            Room entities/DAOs (songs, events, playlists)
├── lastfm/        Last.fm Web API client
├── recommend/     ScrobblerImporter/Sync, ScrobbleSink, TelemetryStore
server/            Python text tower (/health, /auth/*, /embed, SQLite auth/cache)
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
| `RadioStarPlaylistRepository` | Six-playlist generation, persistence, schedules, DJ queue. |
| `PlaylistSchedule` | 06:00 playlist day and once-per-ISO-week rule. |
| `ImportedMostSongs` | Imported top tracks, cached YouTube resolution, Weekly/Monthly Most merge. |
| `ListenStore` | Append-only JSONL imported listens, dedupe and count. |
| `ListenHistory` | Normalised `title|primary artist` keys. |

### 5.3 Playback and analysis (`playback/`)

- `MusicService`: MediaSessionService; owns the player, tap, embedder, DJ
  announcer, settings mirror.
- `TapAudioProcessor`: ring buffer, mono downmix, 10 s windows, RMS; never writes audio.
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
- JSONL:
  - `radiostar_listens.jsonl` — imported listens, append-only, deduplicated.
  - `radiostar_reactions.jsonl` — likes/dislikes with local sequence and
    tombstones; `song.liked` remains the UI projection.
  - `radiostar_telemetry.jsonl` — local opt-in play/pause/skip/seek/complete/
    replay/like/dislike events, including album and Go Beyond facets.
- DataStore:
  - `RadioStarDjKey` (AI DJ on/off), `RadioStarServerKey`,
    `RadioStarProfilesKey`, playlist stamps, per-service import checkpoints,
    last result/total/check time, DJ provider/model, destination and telemetry
    preferences.
- Android Keystore:
  - AES/GCM key material for the DJ API key (`SecureKeyStore`).

### 5.5 External text tower (`server/`)

- Loads CLAP text tower once (~20 s).
- `GET /health` → `{"ok": true}`.
- `POST /embed` → 512-d unit vectors, SQLite cache, one lock around the model.
- `POST /embed/text` → 512-d vectors for free-text prompts (colour and mood
  cards), cached in the same SQLite table.
- Authenticated with per-device opaque tokens; accounts and devices in
  `server/data/auth.sqlite`, Argon2id passwords, one-time pairing codes.
- Binds `127.0.0.1:8787` and expects Cloudflare Tunnel or another HTTPS proxy.

## 6. Runtime View

C4 dynamic views: [`c4-model.md` §4](c4-model.md#4-level-4--code-and-dynamic-views).

### 6.1 Daily and weekly playlists

```text
app start / Home init / 10-minute tick
  → PlaylistSchedule.plan(06:00 playlist day, dailyStamp, weeklyStamp)
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
- The application scope checks every 10 minutes while the process is alive. A
  session left open across 06:00 rebuilds the daily lists without a restart.
  A process start also checks immediately.
- Fixed ids: `LP_RS_DAILY_DISCOVER`, `LP_RS_DAILY_JAMS`,
  `LP_RS_DAILY_EXPLORE`, `LP_RS_WEEKLY_DISCOVER`, `LP_RS_WEEKLY_JAMS`,
  `LP_RS_WEEKLY_EXPLORE`.

### 6.2 Playlist generation details

- The playlist day rolls over at **06:00 local time** (not midnight). A
  background check every 10 minutes catches an app left open overnight.
- History windows: 7 days daily, 30 days weekly. Imported listens are the
  primary source; local event rows are only the fallback when no imported
  history exists. If the window is empty, the newest imported listens are used
  rather than Metrolist's local table.
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
| ListenBrainz | `/1/user/{user}/listens` (`max_ts`, count) — implemented | `/1/submit-listens` — implemented |
| Self-hosted | same ListenBrainz-compatible endpoints on a base URL — implemented | same — implemented |

The RadioStar settings screen selects the service and shows only the fields
that service needs. Before downloading, it asks the service for the total
listen count; the progress bar and the `fetched / total` label use that number.
A run that ends short is marked incomplete, keeps its checkpoint and retries;
if the local per-service count is below the service total, the importer
rebuilds from zero (repairing old capped runs). Incremental runs read from the
newest page and stop at the saved timestamp, so `checked 100 newest of 11,333`
means the first page was newer than the checkpoint, not that the service was
only partially read. Settings shows the local listen count, the last service
total, the last check time and a short explanation. The last result is
persisted. The sync frequency is startup, 15 min, 1 h, 6 h, daily, weekly or
off. A completed import requests an immediate playlist refresh.

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

- Imported listens contribute title/artist text to the taste centroid, the
  heard-key exclusion set and the primary radio anchors.
- Imported top tracks are resolved to YouTube ids via a bounded, cached search.
  Local `event` rows are only the fallback when no imported anchors resolve.
- A window with no imported listens falls back to the newest imported history
  before considering the local Metrolist table.
- The upstream **Weekly Most** and **Monthly Most** playlists merge the top
  imported tracks for their window ahead of local `event` rows. Importing new
  listens clears their sync stamps, so opening Stats rebuilds them.
- Local and imported duplicates collapse by normalised title/artist and
  timestamp proximity.

Generic cold start:

- No scrobbler and little local history → a generic seed (charts/genres) and a
  visible "generic" label. Never present generic lists as personal.
- Once imported listens exist, the same pipeline becomes personal.

Outbound scrobbling:

- A separate **Scrobble service** setting chooses the destination: Off
  (default), Last.fm, ListenBrainz, Custom (ListenBrainz-compatible) or
  External scrobbler. Off and External never submit anything; External is for
  users who already run Pano Scrobbler or another Android scrobbler.
- `ScrobbleManager` keeps its delay threshold and calls `ScrobbleSink`, which
  routes now-playing and completed listens to the chosen destination.
- Last.fm uses the existing session and `track.scrobble`; ListenBrainz and
  Custom POST `/1/submit-listens`. Custom URLs accept the server root or the
  full endpoint.
- A destination token falls back to the import token, so a single ListenBrainz
  token can cover both directions. Offline retry is still a follow-up.
- Reactions mirror independently of listen submission: Last.fm love/unlove,
  ListenBrainz and compatible love/hate feedback. Outbound listen submission
  has its own switch, so RadioStar can stay silent while another Android
  scrobbler submits, yet still own likes/dislikes and read history.
- Cross-device settings and reactions sync through the RadioStar server
  account (D-22). The server is authoritative; conflicts are last-writer-wins
  on `(updated_at_ms, device_id)`, and deletions are tombstones.

### 6.4 AI DJ session

Entry point: a compact Home block separate from playlists, present only when
**Settings → RadioStar → AI DJ** is on:

```text
🍒  DJ Cherry
    Start a new session
```

Tapping the card starts a fresh session; the gear opens the DJ settings panel.

Panel controls:

- A radial wheel with three dimensions:
  - familiar tracks: Never / Sometimes / Frequently
  - tracks far from the taste: Never / Sometimes / Frequently
  - tracks that look like the taste: Never / Sometimes / Frequently
- The same wheel selects the persona; a segmented control mirrors every wheel
  stop for accessibility.
- Personas: 🍒 Cherry (default), 🍋 Lemon, 🫐 Berry, 🍌 Banana, 🍓 Strawberry,
  🥥 Coco. Each has a short personality, TTS pitch/rate and wording.
- The card starts a **new** session every time; leaving the app and returning
  does not resume the old one. The gear opens settings. Save persists the
  wheel/persona and starts a new session. The AI provider (OpenRouter, OpenAI,
  Gemini or custom) is chosen in Settings → AI, not in the DJ panel.
- The DJ session is not a playlist. It replaces the queue only when started,
  keeps an empty backing queue so auto-load cannot append unrelated tracks, and
  ends as soon as the user starts another queue or the session finishes.
- On start the player is prepared paused. The intro is generated from the first
  track and persona (AI when enabled, track-specific template otherwise), and
  playback begins only after the utterance finishes.
- The speaking overlay opens only while the player sheet is expanded. Starting
  a DJ session expands it; opening Settings or Search hides the overlay. Swipe
  down or Back dismisses the overlay for the current utterance.
- Turning **AI DJ** off removes the Home card, the speaking overlay, the DJ
  settings and the DJ section in AI settings; no DJ announcement runs.
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

### 6.5 DJ speech and speaking overlay

- Platform TTS, 8 s lookahead before the next track.
- Duck player to 22%, partial wake lock, restore effective volume.
- The session intro is spoken before playback starts; the player remains
  paused until the utterance completion callback.
- The overlay is shown only while the player sheet is expanded. Swipe down or
  Back dismisses it for the current utterance.
- Screen-off verified: announce → duck → utterance → done → restore.

### 6.6 Advanced telemetry

Telemetry is local, opt-in and off by default. `TelemetryStore` appends JSONL
events to the app's private files directory only when the user enables it.

Recorded fields:

- event type: play, pause, skip, seek, completion, replay, like or dislike;
- song id, title, artist and album;
- position and duration;
- wall-clock time and process session id;
- playlist name, queue seed and Go Beyond facets (country, genre, period,
  colour, mood).

`TelemetryInsights` reduces the log to completion/skip/early-skip rates,
discovery rate, session count, active hours, top artists and songs, plus a
bounded affinity for every Go Beyond facet. `TelemetryTaste` turns the same
events into decayed song and artist scores used by the DPP quality term, the DJ
buckets and the Go Beyond ranker. Nothing is uploaded, and no audio or device
identifier is written. The settings screen shows the stored event count and
the derived listening signal, and can delete the local file.

### 6.7 Go Beyond

`Search` has a second tab beside the search field. It presents country, genre,
artist, period, colour and mood sections plus a custom builder.

- Country uses all ISO 3166 countries from `Locale.getISOCountries()`, with
  English names, flag emoji, demonyms and fuzzy aliases. A searchable picker
  lists every country; the section row shows personalized picks first.
  Country journeys are origin-checked: `ArtistOriginResolver` batches artist
  names against MusicBrainz, caches ISO country codes locally for 30 days and
  drops artists known to come from a different country. Unknown origins stay
  only as a fallback, so a playlist cannot silently become international.
- Genres are a broad built-in catalog (about 80 entries) plus a search-or-type
  picker with a `+` action for any custom genre. `GoBeyondResolver` maps typos
  and aliases such as `hihop` or `shoegaz` to canonical values and sanitizes the
  rest.
- Period supports predefined decades and a free year range.
- Colour and mood are atmospheres, not title keywords. Colour queries use
  descriptors such as hazy, velvet, psychedelic and slow; the colour name is
  never sent as a search keyword and tracks whose title or artist literally
  contains it receive a penalty.
- `GoBeyondFuzzy` and `GoBeyondResolver` accept typos, accents, aliases and
  reordered words (`purpel`, `shoegaz`, `melancolic`, `Brasil`, `hihop`).
  Stupid or unsafe input is sanitized or dropped.

Tapping a card:

1. builds target-specific searches. Artist journeys search only that artist's
   catalogue and then hard-filter every result to the artist (covers and
   tributes are removed). Country journeys search the demonym and mine curated
   country playlists; when enough curated tracks exist, they become the whole
   candidate pool. This prevents a "Brazil" journey from becoming generic
   international pop.
2. optionally embeds the category prompt with `POST /embed/text` and the
   candidates with `POST /embed` on the user's own server;
3. ranks only inside that target pool with `GoBeyondRanker`: semantic
   similarity, telemetry song/artist scores, recent and skipped memory, a small
   unheard-artist bonus and an artist cap (disabled for artist journeys);
4. writes the result as a normal local playlist with a stable `LP_RS_GO_*` id.

The landing view is personalized locally and cheaply. `GoBeyondPersonalize`
combines telemetry gateway affinity, top/recent/imported artists and albums,
active hours, skip rate and variety appetite to order every section and to
produce a "Picked from your listening" row. It is fuzzy string matching and
bounded arithmetic only: no network and no embedding call.

Artwork is generated per card: flags for countries, artist photos from Room
when present (initials or album art otherwise), abstract genre art, era rings
with the decade label, literal colour palettes, and mood atmospheres. A title
gradient keeps text readable over the reduced-opacity art. Cards use a fixed
height so the Canvas artwork always has a measurable surface.

The MusicBrainz lookup sends only artist names, is cached, and runs only for a
country journey. It is best-effort: a network failure keeps the search/playlist
provenance and generation continues. The landing view uses cache-only origin
hints, never a network request.

Playback is a normal `ListQueue`; the generated playlist name carries the
facets, and `MusicService` copies them into telemetry. Pull-to-refresh rebuilds
the same journey from the saved selection.

## 9. Architecture Decisions

See `docs/decisions.md`:

- D-01..D-10: audio tap, CLAP model, DPP kernel, identity, text tower, engine.
- D-11: engine ships in release; arm64 only; APK sizes.
- D-12: DJ voice and ListenBrainz CSV import.
- D-13: on-device tempo/key and audio ordering.
- D-14: playlists, not playback modes; six scheduled playlists.
- D-15: AI DJ is a session, not a playlist.
- D-16: imported-first history and scrobble destination.
- D-17: RadioStar brand and icon.
- D-18: 06:00 playlist day and imported-first mixes.
- D-19: history-based Weekly/Monthly Most.
- D-20: local advanced telemetry.
- D-22: server-synced settings and reactions.
- D-23: Go Beyond gateway playlists and telemetry-driven journey ranking.

## 10. Quality Requirements

| Scenario | Expected |
| --- | --- |
| Open Home five times on the same day | Only the first check generates daily lists; the rest load the existing rows. |
| Wait until the next 06:00 | Daily lists regenerate once; weekly stay. |
| Kill the app during generation | Stamp not advanced; next launch retries; old playlists remain. |
| Import 5,000 Last.fm scrobbles | Paged, deduped, resumable; no duplicate rows; UI remains responsive. |
| Lose network mid-import | Checkpoint stays at last committed page; retry resumes. |
| Play a playlist | Native queue; automatic generation does not modify it. |
| Start DJ | Fresh queue, prepared paused; intro speaks first; no effect outside the session. |
| No scrobbler, no history | Empty shelf or clearly marked generic lists. |
| Embedding server offline | Playback unaffected; old playlists remain; new generation skipped. |

## 11. Risks and Technical Debt

- **Cold-start blindness** for users with neither imported nor local history;
  the generic seed/label path remains a product risk.
- Text-only candidates cannot have BPM/key until played; harmonic ordering is
  partial.
- The laptop dependency limits playlist generation away from home.
- Generated playlist rows can accumulate orphan songs; a cleanup policy is
  pending.
- Last.fm API keys are build-time; a user cannot paste a Last.fm key in the
  current UI. ListenBrainz and self-hosted use a token.
- Release builds are unsigned; no store distribution.
- DJ personas and the panel are implemented; the candidate pool is the
  generated playlists, not a dedicated DJ candidate engine yet.
- Outbound Last.fm, ListenBrainz and Custom are implemented; offline retry is
  still a follow-up.
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
