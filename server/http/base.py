"""Shared HTTP plumbing: responses, auth, bodies and client identity."""

from __future__ import annotations

import ipaddress
import json
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
from typing import Any
from urllib.parse import urlsplit

from ..api.validation import MAX_AUTH_BODY_BYTES, BadRequest
from ..auth import Session, Unauthorized
from ..context import AppContext
from ..net import is_loopback

SOCKET_TIMEOUT_SECONDS = 15


class BaseHandler(BaseHTTPRequestHandler):
    """Base handler with the response and authentication helpers."""

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
        sys.stderr.write(f"{self.client_ip()} {fmt % args}\n")

    def send_json(
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

    def send_error_json(
        self,
        status: HTTPStatus | int,
        message: str,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        self.send_json(status, {"error": message}, extra_headers)

    def send_unauthorized(self) -> None:
        self.send_error_json(
            HTTPStatus.UNAUTHORIZED,
            "unauthorized",
            {"WWW-Authenticate": 'Bearer realm="radiostar"'},
        )

    def send_rate_limited(self, retry_after: int) -> None:
        self.send_error_json(
            HTTPStatus.TOO_MANY_REQUESTS,
            "too many requests",
            {"Retry-After": str(retry_after)},
        )

    def route_path(self) -> str:
        return urlsplit(self.path).path

    def query_string(self) -> str:
        return urlsplit(self.path).query

    def client_ip(self) -> str:
        peer = str(self.client_address[0]) if self.client_address else "unknown"
        if self.ctx.trust_proxy == "cloudflare" and is_loopback(peer):
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

    def session(self, allow_anonymous: bool = False) -> Session | None:
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

    def require_session(self) -> Session:
        session = self.session()
        if session is None:
            raise Unauthorized("unauthorized")
        return session

    def rate(self, key: str, limit: int, window_seconds: int) -> bool:
        return self.ctx.limiter.allow(key, limit, window_seconds)

    # -- body ----------------------------------------------------------

    def read_json_body(self, max_bytes: int) -> Any:
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

    def read_small_json(self) -> dict[str, Any]:
        payload = self.read_json_body(MAX_AUTH_BODY_BYTES)
        if not isinstance(payload, dict):
            raise BadRequest("body must be a JSON object")
        return payload
