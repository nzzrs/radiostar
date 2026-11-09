# RadioStar server

One Python process that adds CLAP text embeddings and cross-device sync to
RadioStar. The phone sends artist/title text or small sync items; the server
never receives audio.

It binds `127.0.0.1:8787` by default. Put Cloudflare Tunnel, Tailscale Serve or
another HTTPS reverse proxy in front; never expose port 8787 directly.

## Quick start

From the repository root:

```sh
python3 -m venv .venv
.venv/bin/pip install --index-url https://download.pytorch.org/whl/cpu torch torchaudio torchvision
.venv/bin/pip install -r server/requirements.txt

.venv/bin/python -m server setup    # owner account + pairing code
.venv/bin/python -m server serve    # http://127.0.0.1:8787
```

`setup` is idempotent: it creates the owner account if needed, then prints a
one-time pairing code. The first `serve` loads the CLAP text tower (about 20 s).
Activate the venv (`source .venv/bin/activate`) or prefix later commands with
`.venv/bin/`.

In the app: `Settings -> RadioStar -> Server account -> Pairing code`.

For a non-interactive setup, pipe the password:

```sh
printf '%s\n' "$PASSWORD" | python -m server setup --password-stdin
```

### Already have a venv?

Any Python 3.10+ environment with `server/requirements.txt`, PyTorch and the
CLAP checkpoint works. Point the CLI at it directly; there is no fixed venv
path:

```sh
/path/to/venv/bin/python -m server serve
```

## Link another device

Generate a fresh code on the server:

```sh
python -m server pair --user owner
```

A linked device can also generate a code from its own account screen. Codes are
single-use and valid for five minutes. Username/password login works too.

## Commands

| Command | Purpose |
| --- | --- |
| `python -m server` | Start the service (same as `serve`). |
| `python -m server setup` | Create the owner account and print a pairing code. |
| `python -m server serve` | Load the model and serve HTTP. |
| `python -m server pair --user owner` | Print a new pairing code. |
| `python -m server devices --user owner` | List linked devices. |
| `python -m server revoke <device-id>` | Revoke one device. |
| `python -m server passwd --user owner` | Change a password; revokes every session. |
| `python -m server user-create --user family` | Create another account. |
| `python -m server doctor` | Check Python modules, paths, database and port. |

`python server/admin.py <command>` still works for older scripts. `init` is an
alias for `setup`, and `device-revoke` for `revoke`.

## Configuration

Environment variables have safe defaults. Command line flags override them.

| Variable | Default | Meaning |
| --- | --- | --- |
| `RADIOSTAR_HOST` | `127.0.0.1` | Bind address. Keep loopback in production. |
| `RADIOSTAR_PORT` | `8787` | Bind port. |
| `RADIOSTAR_AUTH_MODE` | `required` | `required`, `optional` or `off` (development only). |
| `RADIOSTAR_DB_PATH` | `server/data/auth.sqlite` | Accounts, devices and sync rows. |
| `RADIOSTAR_CACHE_PATH` | `server/data/text_cache.sqlite` | Text embedding cache. |
| `RADIOSTAR_CLAP_CHECKPOINT` | unset | Optional path to `630k-audioset-best.pt`. |
| `RADIOSTAR_TRUST_PROXY` | empty | Set `cloudflare` to trust `CF-Connecting-IP` from a loopback peer. |
| `RADIOSTAR_MAX_THREADS` | `16` | Handler thread cap. |
| `RADIOSTAR_EMBED_DAILY_ITEMS` | `50000` | Daily embedding quota per device; `0` disables it. |

Useful one-off flags:

```sh
python -m server serve --port 9000 --auth-mode optional
python -m server doctor --db /path/auth.sqlite --cache /path/cache.sqlite
```

## API

`GET /health` is public and deliberately minimal:

```json
{"ok": true}
```

`POST /embed` and `POST /embed/text` require a device Bearer token:

```json
{"items": [{"artist": "Lenin Ramirez", "title": "Todo Lo Fue"}]}
{"texts": ["purple haze", "melancholic"]}
```

Both return one unit vector per input, in request order:

```json
{"vectors": [[0.0123, -0.0456, 0.0789]]}
```

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

Sync endpoints use the same device tokens:

```text
GET    /sync/pull?since=<seq>&limit=<n>   items changed after seq
POST   /sync/push                         up to 500 config/reaction items
GET    /sync/status                       counts and current sequence
DELETE /sync                              wipe this account's sync data
```

`/sync/push` resolves conflicts by last-writer-wins on
`(updated_at_ms, device_id)` and returns losing rows in `conflicts`. Deletions
are tombstones. Secret config values are encrypted on the phone before upload;
the server only stores ciphertext.

Access tokens last 15 minutes. Refresh tokens last 30 days and rotate; reuse
outside a short grace window revokes the device family. Only SHA-256 hashes are
stored server-side.

## Security

- Passwords use Argon2id. Accounts are created locally; there is no public
  registration endpoint.
- Pairing codes are single-use, hashed and rate limited.
- `/embed` authenticates before reading the body.
- Limits: 60 embed requests/min per device, 300/min per IP, 1 MiB body, 256
  items or 32 texts per request, a configurable daily quota, a thread cap and a
  15 s socket timeout.
- Errors do not echo internal exceptions or a version banner.
- No cookies, CORS or web admin UI, so there is no CSRF surface.

Back up `server/data/auth.sqlite` only if you can protect it. The database is
local and gitignored.

## Docker

```sh
cd server
UID=$(id -u) GID=$(id -g) docker compose up --build -d
docker compose exec -it embeddings python -m server setup
```

Compose uses host networking so the service stays on `127.0.0.1:8787`, runs as
your UID/GID, and mounts the auth database and text cache. It reuses an existing
CLAP checkpoint; override its host path when needed:

```sh
RADIOSTAR_CHECKPOINT=/path/to/630k-audioset-best.pt docker compose up -d
```

Without a checkpoint mount, `laion_clap` downloads the model on first use.

## HTTPS proxy

Example Cloudflare Tunnel configuration:
[`cloudflared.example.yml`](cloudflared.example.yml).

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
or a WAF challenge can block native app requests; exclude the hostname or API
route.

The tunnel encrypts the public leg, but Cloudflare can see artist/title metadata
in transit. If that is not acceptable, use Tailscale Serve or another WireGuard
tunnel instead of a public hostname.

## Troubleshooting

Run:

```sh
python -m server doctor
```

It reports missing Python modules, unwritable paths, an unreadable database and
a busy port. Common cases:

- **No account**: run `python -m server setup`.
- **`address already in use`**: stop the other process, or use `--port`.
- **Model load fails**: check `RADIOSTAR_CLAP_CHECKPOINT`; leave it unset to let
  `laion_clap` use or download its own checkpoint.
- **Startup takes about 20 s**: the text tower loads once, not per request.
- **Docker has no checkpoint**: set `RADIOSTAR_CHECKPOINT` or let the image
  download the model.

## Tests

```sh
python -m pytest server/tests -q
```

The suite uses a fake embedder and a temporary database; it does not load the
CLAP model.

## Design notes

Do **not** move the CLAP text tower onto the phone. The phone already carries
the audio tower, and both towers are too large to keep side by side. The phone
stores only the 512-d text vectors and compares them with audio embeddings from
the same shared space.

The cache is keyed by lowercased `artist` + `title`. Delete it to force a cold
run; each new pair costs a model forward pass. With more than one user,
namespace this cache per user or accept the timing side channel.
