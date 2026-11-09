"""Public health check."""

from __future__ import annotations

from http import HTTPStatus

from .protocol import RequestHandler


def health(handler: RequestHandler) -> None:
    handler.send_json(HTTPStatus.OK, {"ok": True})
