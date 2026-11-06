# RadioStar embedding server

Small LAN HTTP service in front of `server/text_embedder.py`. The phone sends
`artist`/`title` pairs and gets 512-d CLAP text vectors back.

## Start

Use the project venv. Run from the repo root or anywhere; the service chdirs
to the repo root itself.

```sh
/tmp/opencode/clap-venv/bin/python server/app.py
```

It loads the text tower once (about 20 s) and then prints:

```
ready: http://192.168.x.y:8787
```

The service binds `0.0.0.0:8787`. Allow port 8787 through the laptop firewall
if the phone cannot reach it.

## Endpoints

`GET /health`

```json
{"ok": true, "model": "clap-htsat-tiny"}
```

`POST /embed`

Request:

```json
{"items": [{"artist": "Lenin Ramirez", "title": "Todo Lo Fue"}]}
```

Response:

```json
{"vectors": [[0.0123, -0.0456, ...]]}
```

One 512-float vector per item, in request order. Vectors are unit length.
Embeddings are cached in `spike/clap/out/text_cache.sqlite`, so repeats are
fast. Bad input returns `400` with `{"error": "..."}`; unexpected failures
return `500` with `{"error": "..."}` and a traceback on stderr. At most 256
items per request.

Limits: request body 1 MiB. The model is guarded by one lock, so the service
embeds one batch at a time; concurrent requests wait instead of corrupting the
model.

## Keep the text tower on the laptop

Do **not** move the CLAP text tower onto the phone. The phone already carries
the audio tower, and both towers are too large to keep side by side. The phone
only stores the 512-d text vectors and compares them with audio embeddings
from the same shared space.

## Security

No authentication, no TLS: this is a LAN-only developer service. Bind it to
your home network, never expose port 8787 to the internet, and stop it when you
are not testing. Track titles sent to it are the same metadata the app already
shows; no audio ever reaches the server.

## Cache

The SQLite cache lives at `spike/clap/out/text_cache.sqlite`, keyed by
lowercased `artist` + `title`. Delete that file to force a cold run (each new
pair costs a model forward pass).
