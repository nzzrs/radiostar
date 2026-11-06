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
injected into the playback queue. Mixes (Discover, Jams, Explore), the DJ voice,
energy ordering and ListenBrainz history import live under
`Settings → RadioStar`.

## Build and run

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=/opt/android-sdk
cd app
./gradlew :app:assembleFossDebug
adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

Only `arm64-v8a` is packaged. A release build is unsigned (no release keystore
in the repository) and measures about 83 MB.

The laptop embedding server is optional but cold candidates need it:

```bash
/tmp/opencode/clap-venv/bin/python server/app.py   # port 8787
```

Set the URL in `Settings → RadioStar → Embedding server`, or for automated
tests:

```bash
adb shell settings put global radiostar_embed_server http://<laptop>:8787
```

## Tests

```bash
python3 -m pytest spike -q                    # kernel + identity, 55 tests
python3 -m unittest scripts/test_listenbrainz_export.py
cd app && ./gradlew :app:testFossDebugUnitTest  # 79 recommendation tests
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

## Decisions

See [docs/decisions.md](docs/decisions.md) for the numbered decisions and the
evidence. [docs/spike.md](docs/spike.md) closes the research spike and lists
what moved into the app.
