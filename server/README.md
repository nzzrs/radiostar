# RadioStar embedding server

Small HTTP service in front of `server/text_embedder.py`. The phone sends
`artist`/`title` pairs and gets 512-d CLAP text vectors back.

The service is authenticated. It binds loopback by default and is designed to
run behind Cloudflare Tunnel (or another HTTPS reverse proxy), never with port
8787 exposed to the internet.

## First run

Create the owner account on the machine that runs the server:

```sh
python server/admin.py init
```

The password is read from the terminal and stored as an Argon2id hash in
`server/data/auth.sqlite`. There are no default credentials.

Start the service with the project venv:

```sh
/tmp/opencode/clap-venv/bin/python server/app.py
```

It loads the text tower once (about 20 s) and prints:

```text
ready: http://127.0.0.1:8787
```

## Link a device

Generate a one-time code (valid 5 minutes) on the server:

```sh
python server/admin.py pair --user owner
```

In the app: `Settings -> RadioStar -> Server account -> Pairing code`. You can
also log in with the username and password. From a linked device, the account
screen can generate a code for another device.

Useful administration commands:

```sh
python server/admin.py devices --user owner
python server/admin.py device-revoke <device-id>
python server/admin.py passwd --user owner
python server/admin.py user-create --user family
```

`passwd` revokes every existing session. The auth database is local and
gitignored; back it up only if you can protect it.

## Endpoints

`GET /health` is public and minimal:

```json
{"ok": true}
```

It deliberately omits the model name and version.

`POST /embed` requires `Authorization: Bearer <access token>`:

```json
{"items": [{"artist": "Lenin Ramirez", "title": "Todo Lo Fue"}]}
```

Response:

```json
{"vectors": [[0.0123, -0.0456, ...]]}
```

One 512-float unit vector per item, in request order. The client sends at most
16 items per request; the server accepts at most 256. Embeddings are cached in
`spike/clap/out/text_cache.sqlite`, so repeats are fast. Bad input returns 400;
missing or expired auth returns 401; quota or rate limits return 429 with
`Retry-After`; unexpected failures return 500 with a generic message and a
traceback on stderr.

Auth endpoints:

```text
POST /auth/login          username, password, device_name
POST /auth/pair           code, device_name
POST /auth/refresh        refresh_token (rotates the pair)
POST /auth/logout         Bearer
GET  /auth/me             Bearer
POST /auth/pairing-code   Bearer
GET  /auth/devices        Bearer
DELETE /auth/devices/{id} Bearer
```

Access tokens last 15 minutes. Refresh tokens last 30 days and rotate; reuse
outside a short grace window revokes the device family. Only SHA-256 hashes of
tokens are stored server-side.

## Security model

- Accounts and devices live in SQLite (`server/data/auth.sqlite`, mode 0600).
- Passwords use Argon2id. Pairing codes are single-use, hashed and rate limited.
- `/embed` authenticates before reading the body, so unauthorized requests do
  not consume model time.
- Limits: embed 60 requests/min per device, 300/min per IP, 1 MiB body, 256
  items, a configurable daily item quota and a hard thread cap. Sockets time
  out after 15 s.
- Server errors do not echo internal exceptions or a version banner.
- No cookies, CORS or web admin UI, so there is no CSRF surface.

Environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `RADIOSTAR_HOST` | `127.0.0.1` | Bind address. Keep loopback in production. |
| `RADIOSTAR_PORT` | `8787` | Port. |
| `RADIOSTAR_AUTH_MODE` | `required` | `required`, `optional` or `off` (development only). |
| `RADIOSTAR_DB_PATH` | `server/data/auth.sqlite` | Auth database. |
| `RADIOSTAR_TRUST_PROXY` | empty | Set `cloudflare` to trust `CF-Connecting-IP` from a loopback peer. |
| `RADIOSTAR_MAX_THREADS` | `16` | Handler thread cap. |
| `RADIOSTAR_EMBED_DAILY_ITEMS` | `50000` | Daily item quota per device. |

## Cloudflare Tunnel

The public TLS endpoint is Cloudflare; the origin stays on loopback. An
example is in [`cloudflared.example.yml`](cloudflared.example.yml):

```yaml
tunnel: <tunnel-id>
credentials-file: /home/<user>/.cloudflared/<tunnel-id>.json
ingress:
  - hostname: embed.example.com
    service: http://127.0.0.1:8787
  - service: http_status:404
```

Run `cloudflared tunnel run`, or install it as a service. Do not publish 8787.

Cloudflare cuts proxied requests after about 100 seconds and returns 524. The
app sends 16-item chunks for that reason. `CF-Connecting-IP` is trusted only
when `RADIOSTAR_TRUST_PROXY=cloudflare` and the peer is loopback. Bot Fight Mode
or a WAF challenge can block native app requests; exclude the hostname or the
API route.

Cloudflare Tunnel encrypts the public leg, but Cloudflare can see the
title/artist metadata in transit. If that is not acceptable, use Tailscale
Serve or another WireGuard tunnel instead of a public hostname. Optional
Cloudflare Access adds a static service token on top; it is not required by
this design. If you enable Access, the app also needs `CF-Access-Client-Id` and
`CF-Access-Client-Secret` headers, which this client does not send yet.

## Docker

```sh
cd server
UID=$(id -u) GID=$(id -g) docker compose up --build -d
```

The compose file uses host networking and listens on `127.0.0.1:8787` only,
runs the service as the host UID/GID, and mounts the auth database and text
cache. Create the account inside the container:

```sh
docker compose exec embeddings python -m server.admin init
```

## Tests

```sh
/tmp/opencode/clap-venv/bin/python -m pytest server/tests -q
```

The suite uses a fake embedder and a temporary auth database; it does not load
the CLAP model.

## Keep the text tower on the laptop

Do **not** move the CLAP text tower onto the phone. The phone already carries
the audio tower, and both towers are too large to keep side by side. The phone
only stores the 512-d text vectors and compares them with audio embeddings
from the same shared space.

## Cache

The SQLite cache lives at `spike/clap/out/text_cache.sqlite`, keyed by
lowercased `artist` + `title`. Delete that file to force a cold run (each new
pair costs a model forward pass). With more than one user, namespace this cache
per user or accept the timing side channel.
