# RadioStar

A YouTube Music client with a local, private recommendation engine. The client
streams from YouTube Music; analysis runs on the device over the audio that is
already playing, so no library of audio files is ever stored.

```
app/     fork of Metrolist (GPL-3.0), pinned at e6fea3b, 2026-11-03
server/  laptop-side CLAP text embeddings for cold candidates
scripts/ ListenBrainz export and other developer tools
spike/   archived research evidence (spike closed 2026-11-06)
docs/    design notes and decisions (docs/idea is gitignored, local only)
```

The engine is complete and runs in the app: a Media3 PCM tap feeds a CLAP-tiny
INT8 model, the session state and the DPP build a slate, and the slate is
turned into generated playlists. RadioStar never changes what the user chose
to play: there is no queue injection and no mix mode.

Home shows **RadioStar playlists**: Daily Discover, Daily Jam, Daily Explore
and their weekly versions. **Search → Go Beyond** turns a country, genre,
artist, period, colour, mood or a custom combination into a local gateway
playlist. Countries come from the full ISO list with flags and fuzzy search;
genres include a custom `+` entry; colour and mood are treated as atmosphere,
not title keywords. Artist journeys stay inside that artist's catalogue and
country journeys verify artist origin. Telemetry decides which areas to
surface and which tracks to keep. The playlist day starts at **06:00 local time**, and
daily/weekly lists are rebuilt from imported listens first, with local events
only as fallback. A separate **AI DJ** block (optional) offers a wheel for
familiar/far/likely tracks and fruit personas. The app never changes the queue
outside an explicit DJ start. A DJ session is not a playlist: it starts fresh,
waits for the track-aware intro before playing, and ends when another queue
starts.

`Settings → RadioStar` imports listens from Last.fm, ListenBrainz or a
self-hosted compatible server into the phone. It shows the incremental page,
local count, service total, last check and a `fetched / total` progress bar.
Sync frequency is configurable and a complete import triggers a playlist
refresh. Imported listens are the primary anchor and taste signal.

**Scrobble service** is a separate destination setting: Off (default), Last.fm,
ListenBrainz, Custom or External scrobbler. **Advanced telemetry** is local,
opt-in and off by default; it never leaves the phone. It records completion,
skip depth, seeks, repeats and explicit reactions, then derives completion and
discovery rates, sessions, active hours, top artists and Go Beyond affinity.

**Sync** (optional) uses the same server account. Settings and like/dislike
reactions sync across linked devices; the server is the authority. Secret
settings are encrypted on the phone with a passphrase-derived key, so tokens,
API keys and the YouTube cookie are stored on the server only as ciphertext.
Reactions can also mirror to the signed-in YouTube account and to the
configured scrobbler (Last.fm love, ListenBrainz love/hate feedback). Turning
off outbound listen submission stops scrobbles but keeps likes and reaction
mirroring, so RadioStar can run beside another Android scrobbler.

## Build and run

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=/opt/android-sdk
cd app
./gradlew :app:assembleFossDebug
adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

Only `arm64-v8a` is packaged. A release build is unsigned (no release keystore
in the repository) and measures about 80 MB.

The laptop embedding server is optional but cold candidates need it. Create the
owner account once, start the server, then link the phone from
`Settings → RadioStar → Server account` (pairing code or password):

```bash
python server/admin.py init
/tmp/opencode/clap-venv/bin/python server/app.py   # 127.0.0.1:8787
```

Put Cloudflare Tunnel or Tailscale in front; do not expose port 8787 directly.
Set the public URL in `Settings → RadioStar → Embedding server`, or for
automated tests:

```bash
adb shell settings put global radiostar_embed_server https://radiostarserver.nzrs.link
```

## Tests

```bash
python3 -m pytest spike -q                    # kernel + identity, 55 tests
/tmp/opencode/clap-venv/bin/python -m pytest server/tests -q   # auth + API
python3 -m unittest scripts/test_listenbrainz_export.py
cd app && ./gradlew :app:testFossDebugUnitTest  # 103 recommendation tests
```

## Branches

- `main`: production. Only changed after the user has tested `dev`.
- `dev`: integration. Features are merged here after their own tests pass.
- `feature/<name>`: one feature per branch, branched from `dev`.

```bash
git switch dev
git switch -c feature/my-feature
# work, commit, test
git switch dev && git merge --no-ff feature/my-feature
```

## Architecture

See [docs/README.md](docs/README.md) for the documentation index.
[docs/architecture.md](docs/architecture.md) has the arc42 description and
[docs/c4-model.md](docs/c4-model.md) the C4 context, container, component,
code and deployment views. It covers playlists, 06:00 scheduling, imported
history, AI DJ sessions, scrobbler import/export, telemetry and deployment.
Credits and licences: [docs/CREDITS.md](docs/CREDITS.md).

## Decisions

See [docs/decisions.md](docs/decisions.md) for the numbered decisions and the
evidence. [docs/spike.md](docs/spike.md) closes the research spike and lists
what moved into the app.
