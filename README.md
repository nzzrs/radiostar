# RadioStar

A private YouTube Music client with a local recommendation engine.

RadioStar streams from YouTube Music and analyses the audio on the phone while
it plays. Audio never leaves the device and no music library is stored.
Recommendations arrive as playlists; the queue is never changed unless you
start an AI DJ.

## What you get

- **Daily and weekly playlists**: Discover, Jam and Explore, rebuilt at 06:00
  local time from imported listens first and local plays as fallback.
- **Go Beyond**: build a gateway playlist from a country, genre, artist,
  period, colour, mood or a custom mix.
- **Imported history**: Last.fm, ListenBrainz or any compatible server.
- **Optional sync**: settings and like/dislike reactions across linked devices.
  Secret settings stay encrypted on the phone.
- **Optional AI DJ**: familiar/far/likely wheel, fruit personas and voice intros.
- **Local telemetry**: opt-in, off by default, never uploaded.

The full behaviour and design are in [`docs/architecture.md`](docs/architecture.md).

## Layout

```text
app/      Android client (fork of Metrolist, GPL-3.0)
server/   optional Python embedding and sync service
scripts/  ListenBrainz export and developer tools
spike/    archived research evidence (closed)
docs/     architecture, decisions, credits
```

## Build the app

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=/opt/android-sdk
cd app
./gradlew :app:assembleFossDebug
adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

Only `arm64-v8a` is packaged. Release builds are unsigned and about 80 MB
because the repository has no release keystore.

## Run the server (optional)

The server provides CLAP text embeddings for cold candidates and cross-device
sync. Playback and local analysis work without it.

```bash
./scripts/setup_server.sh --serve
```

The script creates `.venv` if needed, installs CPU PyTorch and the server
requirements, creates the owner account and prints a pairing code, then serves
on `http://127.0.0.1:8787`. An existing environment can be reused:

```bash
RADIOSTAR_VENV=/path/to/venv ./scripts/setup_server.sh
```

Or install and run the steps by hand:

```bash
python3 -m venv .venv
.venv/bin/pip install --index-url https://download.pytorch.org/whl/cpu torch torchaudio torchvision
.venv/bin/pip install -r server/requirements.txt
.venv/bin/python -m server setup
.venv/bin/python -m server serve
```

Then open `Settings -> RadioStar -> Server account` in the app and enter the
pairing code, or set the URL for automated tests:

```bash
adb shell settings put global radiostar_embed_server https://embed.example.com
```

Put Cloudflare Tunnel, Tailscale or another HTTPS proxy in front. Do not expose
port 8787 directly. See [`server/README.md`](server/README.md) for commands,
Docker, configuration and troubleshooting.

## Tests

```bash
python3 -m pytest spike -q
.venv/bin/python -m pytest server/tests -q
python3 -m unittest scripts/test_listenbrainz_export.py
cd app && ./gradlew :app:testFossDebugUnitTest
```

The server suite uses a fake embedder and a temporary database; it never loads
the CLAP model.

## Docs

- [`docs/README.md`](docs/README.md): documentation index.
- [`docs/architecture.md`](docs/architecture.md): arc42 architecture.
- [`docs/c4-model.md`](docs/c4-model.md): C4 views and sequences.
- [`docs/decisions.md`](docs/decisions.md): numbered decisions and evidence.
- [`docs/CREDITS.md`](docs/CREDITS.md): upstream, models and licences.

## Branches

- `main`: production. Changed only after the user tests `dev`.
- `dev`: integration. Features merge here after their tests pass.
- `feature/<name>`: one task per branch, branched from `dev`.

```bash
git switch dev
git switch -c feature/my-feature
# work, test, commit
git switch dev && git merge --no-ff feature/my-feature
```

## Licence

RadioStar is GPL-3.0. Upstream Metrolist notices and model licences are listed
in [`docs/CREDITS.md`](docs/CREDITS.md).
