#!/usr/bin/env python3
"""Authenticated LAN/Cloudflare HTTP service that returns CLAP embeddings.

Run from anywhere; the process chdirs to the repo root so the existing
SQLite cache path in `text_embedder.py` resolves. Standard library plus
argon2-cffi for password hashing.

    /tmp/opencode/clap-venv/bin/python server/app.py

The service binds loopback by default. Put Cloudflare Tunnel (or another
reverse proxy) in front of it; never expose port 8787 directly.
"""

from __future__ import annotations

import ipaddress
import json
import os
import socket
import sys
import threading
import time
import traceback
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

try:  # works both as `python server/app.py` and `python -m server.app`
    from .auth import (
        ACCESS_TTL_SECONDS,
        AuthError,
        DailyQuota,
        InvalidCredentials,
        InvalidPairingCode,
        RateLimiter,
        TokenService,
    )
    from .auth_store import AuthStore
    from .text_embedder import TextEmbedder
except ImportError:  # pragma: no cover - script execution path
    from auth import (  # type: ignore
        ACCESS_TTL_SECONDS,
        AuthError,
        DailyQuota,
        InvalidCredentials,
        InvalidPairingCode,
        RateLimiter,
        TokenService,
    )
    from auth_store import AuthStore  # type: ignore
    from text_embedder import TextEmbedder  # type: ignore

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = Path(__file__).resolve().parent / "data" / "auth.sqlite"

HOST = os.environ.get("RADIOSTAR_HOST", "127.0.0.1")
PORT = int(os.environ.get("RADIOSTAR_PORT", "8787"))
AUTH_MODE = os.environ.get("RADIOSTAR_AUTH_MODE", "required").strip().lower()
TRUST_PROXY = os.environ.get("RADIOSTAR_TRUST_PROXY", "").strip().lower()
MAX_THREADS = int(os.environ.get("RADIOSTAR_MAX_THREADS", "16"))
EMBED_DAILY_ITEMS = int(os.environ.get("RADIOSTAR_EMBED_DAILY_ITEMS", "50000"))
MAX_BODY_BYTES = 1 << 20
MAX_AUTH_BODY_BYTES = 8 << 10
MAX_ITEMS = 256
SOCKET_TIMEOUT_SECONDS = 15
AUTH_MODES = {"off", "optional", "required"}


class BadRequest(ValueError):
    """Client sent a body that does not match the endpoint schema."""


class Unauthorized(AuthError):
    """Missing or invalid bearer token."""


def parse_items(payload: Any) -> list[tuple[str, str]]:
    if not isinstance(payload, dict):
        raise BadRequest("body must be a JSON object")
    items = payload.get("items")
    if not isinstance(items, list):
        raise BadRequest("'items' must be a list")
    if len(items) > MAX_ITEMS:
        raise BadRequest(f"too many items (max {MAX_ITEMS})")

    parsed: list[tuple[str, str]] = []
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            raise BadRequest(f"items[{index}] must be an object")
        artist = item.get("artist")
        title = item.get("title")
        if not isinstance(artist, str) or not artist.strip():
            raise BadRequest(f"items[{index}].artist must be a non-empty string")
        if not isinstance(title, str) or not title.strip():
            raise BadRequest(f"items[{index}].title must be a non-empty string")
        parsed.append((artist, title))
    return parsed


def detect_lan_ip() -> str:
    """Best-effort LAN address. UDP connect sends no packets."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return str(sock.getsockname()[0])
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"
    finally:
        sock.close()


@dataclass
class AppContext:
    store: AuthStore
    tokens: TokenService
    limiter: RateLimiter
    quota: DailyQuota
    embedder: Any
    embed_lock: threading.Lock
    auth_mode: str = AUTH_MODE
    trust_proxy: str = TRUST_PROXY


class EmbedHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "RadioStar"
    sys_version = ""

    ctx: AppContext

    # -- HTTP plumbing -------------------------------------------------

    def setup(self) -> None:
        super().setup()
        self.request.settimeout(SOCKET_TIMEOUT_SECONDS)

    def version_string(self) -> str:
        return "RadioStar"

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write(f"{self._client_ip()} {fmt % args}\n")

    def _send_json(
        self,
        status: HTTPStatus | int,
        payload: dict[str, Any],
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        body = json.dumps(payload).encode("utf-8")
        try:
            self.send_response(int(status))
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(body)))
            self.send_header("cache-control", "no-store")
            self.send_header("x-content-type-options", "nosniff")
            self.send_header("connection", "close")
            for key, value in (extra_headers or {}).items():
                self.send_header(key, value)
            self.end_headers()
            self.close_connection = True
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    def _send_error_json(
        self,
        status: HTTPStatus | int,
        message: str,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        self._send_json(status, {"error": message}, extra_headers)

    def _send_unauthorized(self) -> None:
        self._send_error_json(
            HTTPStatus.UNAUTHORIZED,
            "unauthorized",
            {"WWW-Authenticate": 'Bearer realm="radiostar"'},
        )

    def _path(self) -> str:
        return urlsplit(self.path).path

    def _client_ip(self) -> str:
        peer = str(self.client_address[0]) if self.client_address else "unknown"
        if self.ctx.trust_proxy == "cloudflare" and _is_loopback(peer):
            candidate = self.headers.get("CF-Connecting-IP", "").strip()
            if candidate:
                try:
                    ipaddress.ip_address(candidate)
                except ValueError:
                    return peer
                return candidate
        return peer

    # -- auth ----------------------------------------------------------

    def _bearer(self) -> str | None:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return None
        token = header[len("Bearer ") :].strip()
        return token or None

    def _session(self, allow_anonymous: bool = False):
        if self.ctx.auth_mode == "off":
            return None
        token = self._bearer()
        if token is None:
            if allow_anonymous and self.ctx.auth_mode == "optional":
                return None
            raise Unauthorized("unauthorized")
        session = self.ctx.tokens.authenticate(token)
        if session is None:
            if allow_anonymous and self.ctx.auth_mode == "optional":
                return None
            raise Unauthorized("unauthorized")
        return session

    def _rate(self, key: str, limit: int, window: int) -> bool:
        return self.ctx.limiter.allow(key, limit, window)

    # -- body ----------------------------------------------------------

    def _read_json_body(self, max_bytes: int) -> Any:
        raw_length = self.headers.get("content-length")
        if raw_length is None:
            raise BadRequest("content-length header required")
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise BadRequest("invalid content-length header") from exc
        if length < 0 or length > max_bytes:
            raise BadRequest(f"body too large (max {max_bytes} bytes)")

        raw = self.rfile.read(length)
        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise BadRequest(f"invalid JSON: {exc.msg}") from exc

    # -- GET -----------------------------------------------------------

    def do_GET(self) -> None:
        path = self._path()
        if path == "/health":
            self._send_json(HTTPStatus.OK, {"ok": True})
            return
        if path == "/auth/me":
            self._handle_me()
            return
        if path == "/auth/devices":
            self._handle_devices()
            return
        self._send_error_json(HTTPStatus.NOT_FOUND, "not found")

    def _handle_me(self) -> None:
        try:
            session = self._session()
        except Unauthorized:
            self._send_unauthorized()
            return
        if session is None:
            self._send_unauthorized()
            return
        self._send_json(
            HTTPStatus.OK,
            {
                "username": session.username,
                "device_id": session.device_id,
                "device_name": session.device_name,
                "expires_in": ACCESS_TTL_SECONDS,
            },
        )

    def _handle_devices(self) -> None:
        try:
            session = self._session()
        except Unauthorized:
            self._send_unauthorized()
            return
        if session is None:
            self._send_unauthorized()
            return
        self._send_json(
            HTTPStatus.OK,
            {"devices": self.ctx.tokens.list_devices(session.user_id, session.device_id)},
        )

    # -- POST ----------------------------------------------------------

    def do_POST(self) -> None:
        path = self._path()
        try:
            if path == "/auth/login":
                self._handle_login()
            elif path == "/auth/pair":
                self._handle_pair()
            elif path == "/auth/refresh":
                self._handle_refresh()
            elif path == "/auth/logout":
                self._handle_logout()
            elif path == "/auth/pairing-code":
                self._handle_pairing_code()
            elif path == "/embed":
                self._handle_embed()
            else:
                self._send_error_json(HTTPStatus.NOT_FOUND, "not found")
        except Unauthorized:
            self._send_unauthorized()
        except (InvalidCredentials, InvalidPairingCode):
            self._send_error_json(HTTPStatus.UNAUTHORIZED, "invalid credentials")
        except AuthError:
            self._send_error_json(HTTPStatus.UNAUTHORIZED, "unauthorized")
        except BadRequest as exc:
            self._send_error_json(HTTPStatus.BAD_REQUEST, str(exc))
        except Exception:
            traceback.print_exc(file=sys.stderr)
            self._send_error_json(HTTPStatus.INTERNAL_SERVER_ERROR, "internal error")

    def _read_small_json(self) -> dict[str, Any]:
        payload = self._read_json_body(MAX_AUTH_BODY_BYTES)
        if not isinstance(payload, dict):
            raise BadRequest("body must be a JSON object")
        return payload

    def _handle_login(self) -> None:
        ip = self._client_ip()
        payload = self._read_small_json()
        username = payload.get("username")
        password = payload.get("password")
        device_name = payload.get("device_name", "Unknown device")
        if not isinstance(username, str) or not isinstance(password, str):
            raise BadRequest("username and password are required")
        if not self._rate(f"login:ip:{ip}", 5, 60):
            self._send_error_json(HTTPStatus.TOO_MANY_REQUESTS, "too many requests", {"Retry-After": "60"})
            return
        if not self._rate(f"login:user:{username.casefold()}", 5, 60):
            self._send_error_json(HTTPStatus.TOO_MANY_REQUESTS, "too many requests", {"Retry-After": "60"})
            return
        issued = self.ctx.tokens.login(username, password, str(device_name))
        self._send_json(HTTPStatus.OK, issued.as_dict())

    def _handle_pair(self) -> None:
        ip = self._client_ip()
        payload = self._read_small_json()
        code = payload.get("code")
        device_name = payload.get("device_name", "Unknown device")
        if not isinstance(code, str):
            raise BadRequest("code is required")
        if not self._rate(f"pair:ip:{ip}", 5, 600):
            self._send_error_json(HTTPStatus.TOO_MANY_REQUESTS, "too many requests", {"Retry-After": "600"})
            return
        issued = self.ctx.tokens.pair(code, str(device_name))
        self._send_json(HTTPStatus.OK, issued.as_dict())

    def _handle_refresh(self) -> None:
        ip = self._client_ip()
        payload = self._read_small_json()
        refresh_token = payload.get("refresh_token")
        if not isinstance(refresh_token, str):
            raise BadRequest("refresh_token is required")
        if not self._rate(f"refresh:ip:{ip}", 30, 60):
            self._send_error_json(HTTPStatus.TOO_MANY_REQUESTS, "too many requests", {"Retry-After": "60"})
            return
        issued = self.ctx.tokens.refresh(refresh_token)
        self._send_json(HTTPStatus.OK, issued.as_dict())

    def _handle_logout(self) -> None:
        session = self._session()
        if session is None:
            self._send_unauthorized()
            return
        self.ctx.tokens.logout(session.device_id)
        self._send_json(HTTPStatus.OK, {"ok": True})

    def _handle_pairing_code(self) -> None:
        session = self._session()
        if session is None:
            self._send_unauthorized()
            return
        code, expires_at = self.ctx.tokens.create_pairing_code(session.user_id)
        self._send_json(
            HTTPStatus.OK,
            {
                "code": code,
                "expires_at": expires_at,
                "expires_in": max(0, expires_at - int(time.time())),
            },
        )

    def _handle_embed(self) -> None:
        allow_anonymous = self.ctx.auth_mode == "optional"
        session = self._session(allow_anonymous=allow_anonymous)
        identity = session.device_id if session is not None else "anonymous"
        ip = self._client_ip()
        if not self._rate(f"embed:device:{identity}", 60, 60):
            self._send_error_json(HTTPStatus.TOO_MANY_REQUESTS, "too many requests", {"Retry-After": "60"})
            return
        if not self._rate(f"embed:ip:{ip}", 300, 60):
            self._send_error_json(HTTPStatus.TOO_MANY_REQUESTS, "too many requests", {"Retry-After": "60"})
            return

        payload = self._read_json_body(MAX_BODY_BYTES)
        items = parse_items(payload)
        day = time.strftime("%Y-%m-%d", time.gmtime())
        if not self.ctx.quota.take(identity, len(items), day):
            self._send_error_json(HTTPStatus.TOO_MANY_REQUESTS, "daily quota exceeded", {"Retry-After": "3600"})
            return

        with self.ctx.embed_lock:
            vectors = [self.ctx.embedder.embed(artist, title).tolist() for artist, title in items]
        self._send_json(HTTPStatus.OK, {"vectors": vectors})

    # -- DELETE --------------------------------------------------------

    def do_DELETE(self) -> None:
        path = self._path()
        prefix = "/auth/devices/"
        if not path.startswith(prefix):
            self._send_error_json(HTTPStatus.NOT_FOUND, "not found")
            return
        try:
            session = self._session()
        except Unauthorized:
            self._send_unauthorized()
            return
        if session is None:
            self._send_unauthorized()
            return
        device_id = path[len(prefix) :].strip()
        if not device_id or "/" in device_id:
            self._send_error_json(HTTPStatus.BAD_REQUEST, "invalid device id")
            return
        if not self.ctx.tokens.revoke_device(session.user_id, device_id):
            self._send_error_json(HTTPStatus.NOT_FOUND, "device not found")
            return
        self._send_json(HTTPStatus.OK, {"ok": True})


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    """ThreadingHTTPServer with a hard cap on concurrent handler threads."""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        request_handler: type[BaseHTTPRequestHandler],
        max_threads: int = MAX_THREADS,
    ) -> None:
        self._thread_slots = threading.BoundedSemaphore(max(1, max_threads))
        self.request_queue_size = max(16, max_threads * 2)
        super().__init__(server_address, request_handler)

    def process_request(self, request: socket.socket, client_address: tuple[str, int]) -> None:
        if not self._thread_slots.acquire(timeout=5):
            try:
                request.sendall(
                    b"HTTP/1.1 503 Service Unavailable\r\n"
                    b"content-length: 0\r\nconnection: close\r\n\r\n"
                )
            finally:
                request.close()
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._thread_slots.release()
            raise

    def process_request_thread(self, request: socket.socket, client_address: tuple[str, int]) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._thread_slots.release()


def build_context(embedder: Any, db_path: Path | str = DEFAULT_DB) -> AppContext:
    store = AuthStore(db_path)
    return AppContext(
        store=store,
        tokens=TokenService(store),
        limiter=RateLimiter(),
        quota=DailyQuota(EMBED_DAILY_ITEMS),
        embedder=embedder,
        embed_lock=threading.Lock(),
        auth_mode=AUTH_MODE,
        trust_proxy=TRUST_PROXY,
    )


def create_server(
    context: AppContext,
    host: str = HOST,
    port: int = PORT,
) -> BoundedThreadingHTTPServer:
    EmbedHandler.ctx = context
    return BoundedThreadingHTTPServer((host, port), EmbedHandler)


def main() -> int:
    os.chdir(REPO_ROOT)

    if AUTH_MODE not in AUTH_MODES:
        print(f"invalid RADIOSTAR_AUTH_MODE: {AUTH_MODE}", file=sys.stderr)
        return 2

    db_path = Path(os.environ.get("RADIOSTAR_DB_PATH") or DEFAULT_DB)
    store = AuthStore(db_path)
    context = AppContext(
        store=store,
        tokens=TokenService(store),
        limiter=RateLimiter(),
        quota=DailyQuota(EMBED_DAILY_ITEMS),
        embedder=None,
        embed_lock=threading.Lock(),
        auth_mode=AUTH_MODE,
        trust_proxy=TRUST_PROXY,
    )
    if AUTH_MODE == "required" and store.user_count() == 0:
        print(
            "No account exists yet. Run: python server/admin.py init",
            file=sys.stderr,
            flush=True,
        )

    print("loading CLAP text tower (about 20 s) ...", file=sys.stderr, flush=True)
    embedder = TextEmbedder()
    context.embedder = embedder

    server = create_server(context, HOST, PORT)
    lan_ip = detect_lan_ip()

    print(f"ready: http://{lan_ip}:{PORT}", flush=True)
    print(
        f"listening on {HOST}:{PORT}; auth mode={AUTH_MODE}; "
        f"put Cloudflare Tunnel in front, do not expose this port",
        file=sys.stderr,
        flush=True,
    )

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("shutting down", file=sys.stderr, flush=True)
    finally:
        server.server_close()
        store.close()
    return 0


def _is_loopback(value: str) -> bool:
    try:
        return ipaddress.ip_address(value).is_loopback
    except ValueError:
        return False


if __name__ == "__main__":
    raise SystemExit(main())
