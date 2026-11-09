"""Typing contract for route modules, without importing the HTTP handler."""

from __future__ import annotations

from http import HTTPStatus
from typing import Any, Protocol

from ..auth import Session
from ..context import AppContext


class RequestHandler(Protocol):
    """The small surface that API routes need from the HTTP handler."""

    ctx: AppContext

    def session(self, allow_anonymous: bool = False) -> Session | None: ...

    def require_session(self) -> Session: ...

    def send_json(
        self,
        status: HTTPStatus | int,
        payload: dict[str, Any],
        extra_headers: dict[str, str] | None = None,
    ) -> None: ...

    def send_error_json(
        self,
        status: HTTPStatus | int,
        message: str,
        extra_headers: dict[str, str] | None = None,
    ) -> None: ...

    def send_rate_limited(self, retry_after: int) -> None: ...

    def read_json_body(self, max_bytes: int) -> Any: ...

    def read_small_json(self) -> dict[str, Any]: ...

    def client_ip(self) -> str: ...

    def rate(self, key: str, limit: int, window_seconds: int) -> bool: ...

    def route_path(self) -> str: ...
