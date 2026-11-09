"""Embedding endpoints backed by the CLAP text tower."""

from __future__ import annotations

import time
from http import HTTPStatus

from .protocol import RequestHandler
from .validation import MAX_BODY_BYTES, parse_items, parse_texts


def _authorize(handler: RequestHandler) -> str | None:
    """Authenticate and rate-limit an embedding request.

    Returns the quota identity, or ``None`` after a response has already been
    sent. Authentication happens before the body is read.
    """
    allow_anonymous = handler.ctx.auth_mode == "optional"
    session = handler.session(allow_anonymous=allow_anonymous)
    identity = session.device_id if session is not None else "anonymous"
    ip = handler.client_ip()
    if not handler.rate(f"embed:device:{identity}", 60, 60):
        handler.send_rate_limited(60)
        return None
    if not handler.rate(f"embed:ip:{ip}", 300, 60):
        handler.send_rate_limited(60)
        return None
    return identity


def _take_quota(handler: RequestHandler, identity: str, amount: int) -> bool:
    day = time.strftime("%Y-%m-%d", time.gmtime())
    return handler.ctx.quota.take(identity, amount, day)


def embed(handler: RequestHandler) -> None:
    identity = _authorize(handler)
    if identity is None:
        return

    items = parse_items(handler.read_json_body(MAX_BODY_BYTES))
    if not _take_quota(handler, identity, len(items)):
        handler.send_error_json(
            HTTPStatus.TOO_MANY_REQUESTS,
            "daily quota exceeded",
            {"Retry-After": "3600"},
        )
        return

    with handler.ctx.embed_lock:
        vectors = [
            handler.ctx.embedder.embed(artist, title).tolist()
            for artist, title in items
        ]
    handler.send_json(HTTPStatus.OK, {"vectors": vectors})


def embed_text(handler: RequestHandler) -> None:
    identity = _authorize(handler)
    if identity is None:
        return

    texts = parse_texts(handler.read_json_body(MAX_BODY_BYTES))
    if not _take_quota(handler, identity, len(texts)):
        handler.send_error_json(
            HTTPStatus.TOO_MANY_REQUESTS,
            "daily quota exceeded",
            {"Retry-After": "3600"},
        )
        return

    with handler.ctx.embed_lock:
        vectors = [handler.ctx.embedder.embed_text(text).tolist() for text in texts]
    handler.send_json(HTTPStatus.OK, {"vectors": vectors})
