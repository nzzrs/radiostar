"""Config and reaction sync endpoints."""

from __future__ import annotations

import time
from http import HTTPStatus

from .protocol import RequestHandler
from .validation import MAX_BODY_BYTES, parse_query_int, parse_sync_items


def _now_ms() -> int:
    return int(time.time() * 1000)


def pull(handler: RequestHandler) -> None:
    session = handler.require_session()
    if not handler.rate(f"sync:device:{session.device_id}", 120, 60):
        handler.send_rate_limited(60)
        return
    query = handler.query_string()
    since = parse_query_int(query, "since", 0)
    limit = parse_query_int(query, "limit", 1000)
    result = handler.ctx.store.pull_sync(session.user_id, since, limit)
    result["server_time_ms"] = _now_ms()
    handler.send_json(HTTPStatus.OK, result)


def status(handler: RequestHandler) -> None:
    session = handler.require_session()
    result = handler.ctx.store.sync_status(session.user_id)
    result["server_time_ms"] = _now_ms()
    handler.send_json(HTTPStatus.OK, result)


def wipe(handler: RequestHandler) -> None:
    session = handler.require_session()
    handler.ctx.store.wipe_sync(session.user_id)
    handler.send_json(HTTPStatus.OK, {"ok": True})


def push(handler: RequestHandler) -> None:
    session = handler.require_session()
    ip = handler.client_ip()
    if not handler.rate(
        f"sync:device:{session.device_id}", 120, 60
    ) or not handler.rate(f"sync:ip:{ip}", 300, 60):
        handler.send_rate_limited(60)
        return
    items = parse_sync_items(handler.read_json_body(MAX_BODY_BYTES))
    result = handler.ctx.store.push_sync(session.user_id, items, session.device_id)
    result["server_time_ms"] = _now_ms()
    handler.send_json(HTTPStatus.OK, result)
