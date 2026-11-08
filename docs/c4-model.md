# RadioStar — C4 Model

Status: living document, 2026-11-07. Companion to
[`architecture.md`](architecture.md) (arc42). The arc42 Building Block and
Runtime views are implemented here as C4 context, container, component and
code diagrams.

## 0. How to read this document

C4 uses four levels:

| Level | Question | RadioStar scope |
| --- | --- | --- |
| 1. Context | Who uses the system and which external systems does it depend on? | Phone, user, YouTube Music, laptop, scrobblers, TTS |
| 2. Container | Which runnable units and data stores make up the system? | Android app, playback service, recommendation engine, Room, DataStore, laptop service |
| 3. Component | Which components live inside each container and how do they collaborate? | Recommendation, playback/DJ, import/sync, settings/AI |
| 4. Code | Which classes implement the critical paths? | Repository, scheduler, importer, `MusicService`, DJ runtime |

The diagrams use Mermaid `flowchart`, `sequenceDiagram` and `classDiagram`
syntax. They are deliberately renderer-neutral: no external diagram service is
required. Boundaries named "phone", "laptop" and "external" are trust and
deployment boundaries, not Docker containers.

Non-goals of this model:

- no public multi-user backend;
- no audio library or audio upload;
- no collaborative filtering or model training;
- no web player.

## 1. Level 1 — System Context

RadioStar is a single-user Android application. The phone is the system of
record for history, preferences and generated playlists. The laptop embedding
server is optional compute. External scrobblers are optional and off by
default.

```mermaid
flowchart LR
    U["Listener<br/>(person)"]

    subgraph RS["RadioStar<br/>(software system)"]
        APP["RadioStar Android app"]
    end

    YT["YouTube Music / InnerTube<br/>(external system)"]
    EMB["Embedding server<br/>laptop, optional<br/>(external system)"]
    LF["Last.fm<br/>(external system)"]
    LB["ListenBrainz<br/>(external system)"]
    SH["Self-hosted ListenBrainz-compatible server<br/>(external system)"]
    TTS["Android TTS engine<br/>(platform)"]
    EXT["External scrobbler app<br/>Pano, etc.<br/>(external, optional)"]

    U -->|"plays music, imports listens, starts AI DJ"| APP
    APP -->|"streams, metadata, radio candidates"| YT
    APP -->|"POST /embed, GET /health<br/>title and artist only"| EMB
    APP -->|"import listens, optional scrobble"| LF
    APP -->|"import listens, optional scrobble"| LB
    APP -->|"import listens, optional scrobble"| SH
    APP -->|"speaks DJ lines"| TTS
    APP -.->|"selected External means: app does not scrobble"| EXT

    classDef person fill:#f6d6c8,stroke:#8a4b3a,color:#1f1f1f;
    classDef system fill:#d9e8f5,stroke:#356a8a,color:#1f1f1f;
    classDef external fill:#ececec,stroke:#777,color:#1f1f1f;
    class U person;
    class APP system;
    class YT,EMB,LF,LB,SH,TTS,EXT external;
```

### 1.1 Context notes

- **YouTube Music / InnerTube** is the only audio source. Audio is streamed,
  never stored or redistributed.
- **Embedding server** receives title/artist text only, over HTTPS through
  Cloudflare Tunnel (or Tailscale). It is optional; playback and local analysis
  still work without it.
- **Scrobblers** have two independent roles:
  - **Listens import** pulls history into the phone.
  - **Scrobble service** sends new listens to a chosen destination.
- **External scrobbler** is a destination choice, not an API integration:
  RadioStar stays silent while another Android app scrobbles.
- **Android TTS** is the default DJ voice. No voice model is bundled.

## 2. Level 2 — Container View

```mermaid
flowchart TB
    subgraph Phone["Android phone (trust boundary)"]
        UI["Compose UI<br/>Home, Library, Search, Player, Settings, DJ"]
        VM["ViewModels<br/>HomeViewModel, StatsViewModel"]
        REC["Recommendation engine<br/>playlist repository, schedule, mix, DPP"]
        PLAY["Playback service<br/>MusicService + Media3"]
        ANA["Audio analysis<br/>TapAudioProcessor + CLAP INT8"]
        DJ["DJ runtime<br/>DjSessionState, DjAnnouncer, DjAiCommentator"]
        DB[("Room database<br/>song, event, playlist, artist")]
        JSON[("JSONL stores<br/>radiostar_listens.jsonl<br/>radiostar_telemetry.jsonl")]
        DS[("DataStore<br/>settings, checkpoints, stamps")]
        KS[("Android Keystore<br/>DJ API key")]
    end

    subgraph Laptop["Laptop (optional compute)"]
        SRV["Embedding server<br/>server/app.py :8787<br/>device tokens"]
        CACHE[("SQLite embedding cache")]
    end

    YT["YouTube Music"]
    SC["Last.fm / ListenBrainz / self-hosted"]
    TTS["Android TTS"]

    UI --> VM
    UI --> PLAY
    VM --> REC
    PLAY --> ANA
    PLAY --> DJ
    REC --> DB
    REC --> JSON
    REC --> DS
    REC -->|"POST /embed"| SRV
    REC -->|"search, radio candidates"| YT
    PLAY -->|"stream URL, metadata"| YT
    PLAY -->|"optional scrobble"| SC
    PLAY --> JSON
    DJ -->|"speak"| TTS
    DJ --> KS
    SRV --- CACHE
```

### 2.1 Container responsibilities

| Container | Responsibility | Does not do |
| --- | --- | --- |
| Compose UI | Screens, cards, DJ panel/overlay, settings, progress and status | Queue mutation outside the explicitly started DJ session |
| ViewModels | Home shelf, Stats lists, settings state, DJ queue request | Audio decoding or direct network calls |
| Recommendation engine | Schedule, imported-first anchors, embeddings, DPP slate, persist fixed playlist ids | Play audio or change the current queue |
| Playback service | Media3 player, PCM tap, analysis, TTS, ducking, scrobble dispatch, telemetry hooks | Generate playlists or write recommendation policy |
| Audio analysis | 10 s PCM windows, CLAP INT8, RMS, tempo/key profiles | Store audio or upload audio |
| DJ runtime | Session state, intro/links, persona voice, speaking overlay, AI copy fallback | Change the queue outside a session |
| Room | Library, events, generated songs/artists, playlists | Store audio |
| JSONL stores | Append-only imported listens and local telemetry | Hold secrets |
| DataStore | Preferences, import checkpoints, schedule stamps, last import status | Hold large listen history |
| Keystore | AES/GCM key material for the DJ API key | Store plaintext keys |
| Laptop server | CLAP text tower `/embed`, `/health`, SQLite cache | Receive audio or personal identifiers |

## 3. Level 3 — Component View

### 3.1 Recommendation and scheduling components

```mermaid
flowchart LR
    Home["HomeViewModel"]
    Stats["StatsViewModel"]
    Repo["RadioStarPlaylistRepository"]
    Sched["PlaylistSchedule<br/>06:00 local rollover"]
    Bus["RadioStarRefreshBus"]
    Most["ImportedMostSongs<br/>top imported tracks"]
    Store["ListenStore<br/>JSONL listens"]
    Mix["MixBuilder"]
    DPP["DppKernel"]
    Order["AudioOrderer / EnergyOrderer"]
    Emb["EmbeddingClient"]
    Inner["YouTube InnerTube"]
    DB[("Room")]
    Srv["Laptop embedding server"]

    Home --> Repo
    Home --> Bus
    Bus --> Repo
    Stats --> Most
    Repo --> Sched
    Repo --> Most
    Repo --> Store
    Most --> Store
    Most --> Inner
    Most --> DB
    Repo --> Mix
    Mix --> DPP
    Repo --> Order
    Repo --> Emb
    Emb --> Srv
    Repo --> Inner
    Repo --> DB
```

Rules encoded by this component group:

- `PlaylistSchedule` compares the 06:00-based day and ISO week. A successful
  generation writes the stamp; a failure does not.
- Imported listens are the primary anchor source. Local `event` rows are only
  the fallback when no imported history exists.
- The repository writes the same six fixed playlist ids; it never duplicates
  generated playlists.
- `ImportedMostSongs` also feeds the upstream Weekly/Monthly Most playlists.

### 3.2 Playback, analysis and DJ components

```mermaid
flowchart TB
    UI["Player screen / DJ speaking overlay"]
    MS["MusicService"]
    Player["Media3 ExoPlayer"]
    Tap["TapAudioProcessor"]
    Clap["ClapEmbedderImpl<br/>CLAP INT8"]
    State["DjSessionState"]
    Ann["DjAnnouncer"]
    Script["DjScript"]
    AI["DjAiCommentator"]
    TTS["Android TTS"]
    Tele["TelemetryStore"]
    Scrobbler["ScrobbleManager"]
    Sink["ScrobbleSink"]
    Provider["AI provider<br/>OpenRouter / OpenAI / Gemini / custom"]
    Scrobble["Last.fm / ListenBrainz / custom"]
    DB[("Room")]
    JSON[("radiostar_telemetry.jsonl")]

    UI --> MS
    MS --> Player
    MS --> Tap
    Tap --> Clap
    MS --> State
    MS --> Ann
    MS --> Script
    MS --> AI
    Ann --> TTS
    Ann --> State
    AI --> Provider
    MS --> Tele
    Tele --> JSON
    MS --> Scrobbler
    Scrobbler --> Sink
    Sink --> Scrobble
    MS --> DB
```

Important behaviour:

- `MusicService` owns the only queue-mutating entry point for a DJ session.
- A DJ start prepares the player paused, speaks the track-aware intro, and
  calls `play()` only from the utterance completion callback.
- Starting any other queue ends the session and stops announcements.
- The overlay is shown only while the player sheet is expanded. Swipe down or
  Back dismisses the overlay for the current utterance.
- `ScrobbleSink` is a no-op when the destination is Off or External.
- `TelemetryStore` writes only when local telemetry is enabled.

### 3.3 Import, sync and history components

```mermaid
flowchart LR
    Settings["RadioStarScreen"]
    Imp["ScrobblerImporter"]
    Sync["ScrobblerSync"]
    Store["ListenStore<br/>JSONL"]
    DS[("DataStore<br/>checkpoints, last result, last total")]
    API["Last.fm / ListenBrainz / self-hosted"]
    Bus["RadioStarRefreshBus"]
    Repo["RadioStarPlaylistRepository"]
    Stats["StatsViewModel"]
    Most["ImportedMostSongs"]
    DB[("Room")]

    Settings --> Imp
    Sync --> Imp
    Imp --> API
    Imp --> Store
    Imp --> DS
    Imp --> Bus
    Bus --> Repo
    Bus --> Stats
    Stats --> Most
    Most --> Store
    Most --> DB
```

Import semantics:

- Incremental runs read the newest page and stop at the saved timestamp.
- `Incremental check: 100 newest` means the page was newer than the checkpoint,
  not that the service was only partially read.
- If the local service count is below the service total, the importer rebuilds
  from zero as a repair.
- A completed import clears Weekly/Monthly Most stamps and requests a playlist
  refresh.
- Settings shows local count, service total, last check and the incremental
  explanation.

### 3.4 Settings, AI and secure storage

```mermaid
flowchart LR
    Settings["RadioStarScreen"]
    AI["AiSettings"]
    Secure["SecureKeyStore"]
    DS[("DataStore")]
    KS[("Android Keystore")]
    Provider["AI provider"]
    DJ["DjAiCommentator"]
    Ann["DjAnnouncer"]

    Settings --> DS
    AI --> DS
    AI --> Secure
    Secure --> KS
    DJ --> Secure
    DJ --> Provider
    DJ --> Ann
```

- The AI provider, model and DJ key live in **Settings → AI → DJ commentary**.
- Gemini is the default DJ provider; model and base URL are configurable.
- The DJ key is AES/GCM-encrypted with an Android Keystore key.
- Turning **AI DJ** off removes the DJ card, panel, overlay, AI DJ group and
  all announcements.

## 4. Level 4 — Code and Dynamic Views

### 4.1 Playlist refresh sequence

```mermaid
sequenceDiagram
    actor User
    participant App as App / HomeViewModel
    participant Repo as RadioStarPlaylistRepository
    participant Sched as PlaylistSchedule
    participant Store as ListenStore
    participant Most as ImportedMostSongs
    participant YT as YouTube InnerTube
    participant Emb as EmbeddingClient
    participant Mix as MixBuilder
    participant DB as Room

    User->>App: start app / open Home / 10-min tick
    App->>Repo: refreshIfDue(force?)
    Repo->>Sched: scheduleDate(now) and plan(stamps)
    alt not due
        Repo->>DB: load existing cards
        Repo-->>App: cards
    else due
        Repo->>Store: load imported listens
        Repo->>Most: resolve top anchors for window
        Most->>YT: search title + artist
        Most->>DB: insert resolved songs
        Repo->>YT: radio candidates from anchors
        Repo->>Emb: embed pool + imported text
        Emb-->>Repo: vectors
        Repo->>Mix: build each spec with centroid
        Mix-->>Repo: slate
        Repo->>DB: replace fixed playlist ids
        Repo->>Repo: write daily/weekly stamps
        Repo-->>App: cards
    end
```

### 4.2 DJ start sequence

```mermaid
sequenceDiagram
    actor User
    participant Home as HomeScreen
    participant VM as HomeViewModel
    participant MS as MusicService
    participant State as DjSessionState
    participant AI as DjAiCommentator
    participant Script as DjScript
    participant Ann as DjAnnouncer
    participant TTS as Android TTS
    participant Player as Media3 player

    User->>Home: tap AI DJ card
    Home->>VM: buildDjQueue(config)
    VM-->>Home: up to 20 songs
    Home->>MS: playDjSession(items, persona)
    MS->>Player: stop, set items, prepare, playWhenReady = false
    MS->>State: speaking(persona)
    MS->>State: requestPlayerOpen()
    alt AI commentary enabled and key configured
        MS->>AI: intro(persona, first track)
        AI-->>MS: personalised line or null
    end
    alt AI returned nothing
        MS->>Script: intro(persona, first track)
        Script-->>MS: track-specific line
    end
    MS->>Ann: announce(line, onDone)
    Ann->>TTS: speak
    Ann-->>State: speaking + duck
    TTS-->>Ann: utterance done
    Ann->>Player: restore volume
    Ann-->>MS: onDone
    MS->>Player: play()
    State-->>State: playing (overlay hidden)
```

### 4.3 Import sequence

```mermaid
sequenceDiagram
    actor User
    participant UI as RadioStarScreen
    participant Imp as ScrobblerImporter
    participant API as Scrobbler API
    participant Store as ListenStore
    participant DS as DataStore
    participant Bus as RadioStarRefreshBus
    participant Stats as StatsViewModel
    participant Most as ImportedMostSongs

    User->>UI: Import listens
    UI->>Imp: import(config)
    Imp->>API: total count
    Imp->>DS: read checkpoint
    alt local count below service total
        Imp->>Imp: reset checkpoint and repair
    end
    loop newest pages until checkpoint or total
        Imp->>API: fetch page
        Imp->>Store: append deduplicated records
        Imp-->>UI: progress(fetched, total)
    end
    Imp->>DS: checkpoint, last total, last check
    Imp-->>UI: added, fetched, local total
    UI->>Bus: request()
    Bus->>Stats: forced rebuild
    Stats->>Store: top imported listens for window
    Stats->>Most: resolve and insert songs
    Stats->>DS: clear Most stamps
```

### 4.4 Critical classes

```mermaid
classDiagram
    class RadioStarPlaylistRepository {
        +cards: StateFlow
        +refreshIfDue(force: Boolean)
        +buildDjQueue(config: DjConfig): List
    }
    class PlaylistSchedule {
        +scheduleDate(now: LocalDateTime): LocalDate
        +plan(today, dailyStamp, weeklyStamp, force): PlaylistRefreshPlan
    }
    class ImportedMostSongs {
        +topListens(listens, since, limit): List
        +resolve(context, database, since, limit): List
    }
    class ListenStore {
        +load(): List
        +append(records): Int
        +count(): Int
    }
    class MixBuilder {
        +build(session, candidates, kind, k, historyIds, historyKeys): List
    }
    class DppKernel {
        +select(kernel, k): List
    }
    class EmbeddingClient {
        +embed(server, items): List
    }
    class MusicService {
        +playDjSession(items, persona)
        +playQueue(queue, playWhenReady, restoringQueue)
        +adoptQueue(queue, title, initialQueueSize)
    }
    class DjSessionState {
        +state: StateFlow
        +speaking(persona)
        +playing(persona)
        +dismissSpeaking()
        +idle()
    }
    class DjAnnouncer {
        +prepare()
        +announce(text, id, onDone): Boolean
        +shutdown()
    }
    class ScrobblerImporter {
        +import(context, config, onProgress): ListenImportResult
    }
    class ScrobbleSink {
        +nowPlaying(context, artist, track, album, durationSeconds)
        +scrobble(context, artist, track, album, durationSeconds, timestampSeconds)
    }
    class TelemetryStore {
        +record(context, event)
        +count(context): Int
        +clear(context): Boolean
    }

    RadioStarPlaylistRepository --> PlaylistSchedule
    RadioStarPlaylistRepository --> ImportedMostSongs
    RadioStarPlaylistRepository --> ListenStore
    RadioStarPlaylistRepository --> MixBuilder
    MixBuilder --> DppKernel
    RadioStarPlaylistRepository --> EmbeddingClient
    MusicService --> DjSessionState
    MusicService --> DjAnnouncer
    MusicService --> ScrobbleSink
    MusicService --> TelemetryStore
    ScrobblerImporter --> ListenStore
```

## 5. Deployment View

```mermaid
flowchart TB
    subgraph Phone["Samsung Galaxy A52s / Android 8+ (arm64-v8a)"]
        APK["RadioStar APK<br/>debug link.nzrs.radiostar.dev<br/>release link.nzrs.radiostar"]
        ROOM[("Room song.db")]
        JSON[("files/*.jsonl")]
        DS[("DataStore settings.preferences_pb")]
        KS[("Android Keystore")]
        TTS["Android TTS"]
    end

    subgraph Laptop["Laptop (optional)"]
        SRV["server/app.py<br/>127.0.0.1:8787"]
        CACHE[("SQLite embedding cache")]
    end

    YT["YouTube Music / InnerTube"]
    SC["Last.fm / ListenBrainz / self-hosted"]

    APK --- ROOM
    APK --- JSON
    APK --- DS
    APK --- KS
    APK --> TTS
    APK -->|"HTTPS, device token<br/>metadata only"| SRV
    SRV --- CACHE
    APK -->|"HTTPS"| YT
    APK -->|"HTTPS, optional"| SC
```

Deployment notes:

- Release APKs are unsigned in this repository; no store distribution.
- The laptop server requires authentication and is intended for the owner's
  devices. It binds loopback and is published through Cloudflare Tunnel or
  Tailscale with HTTPS.
- Without the laptop, playback, DJ and local analysis work; new text-embedding
  playlists wait until the server is reachable.
- Scrobble tokens stay on the phone. The DJ API key is additionally encrypted.

## 6. Mapping to arc42

| arc42 section | C4 view in this document |
| --- | --- |
| 1. Introduction and Goals | Level 1 context and scope |
| 2. Constraints | Deployment and trust boundaries |
| 3. Context and Scope | Level 1 context |
| 4. Solution Strategy | Container responsibilities and component rules |
| 5. Building Block View | Levels 2 and 3 |
| 6. Runtime View | Level 4 sequences |
| 7. Deployment View | Deployment diagram |
| 8. Cross-cutting Concepts | Security, privacy and failure notes in each view |
| 9. Architecture Decisions | Decisions referenced from component rules |
| 10. Quality Requirements | Scenarios in `architecture.md` |
| 11. Risks and Technical Debt | Risks in `architecture.md` |

## 7. Deliberate boundaries

- **Playlists are artifacts.** The recommendation engine writes rows; it does
  not mutate the active queue.
- **The DJ session is the only queue exception.** It is started explicitly,
  owns an empty backing queue, and ends when the user starts another queue.
- **Imported history is primary.** Local Metrolist events are the fallback for
  users without an import source.
- **Generation is scheduled.** The playlist day starts at 06:00 local; the
  generator is idempotent and writes stamps only on success.
- **Scrobbling is off by default.** Import source and scrobble destination are
  independent settings.
- **Telemetry is local and opt-in.** No event leaves the device.

## 8. Related documents

- [`architecture.md`](architecture.md) — arc42 structure and quality goals.
- [`decisions.md`](decisions.md) — numbered decisions D-01..D-20.
- [`CREDITS.md`](CREDITS.md) — app, model, data and artwork credits.
- [`spike.md`](spike.md) — closed research spike and evidence.
