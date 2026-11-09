"""Request validation shared by the HTTP route modules."""

from __future__ import annotations

import json
from typing import Any
from urllib.parse import parse_qs

MAX_BODY_BYTES = 1 << 20
MAX_AUTH_BODY_BYTES = 8 << 10
MAX_ITEMS = 256
MAX_TEXTS = 32
MAX_SYNC_ITEMS = 500
MAX_SYNC_KEY_LENGTH = 512
MAX_SYNC_PAYLOAD_BYTES = 256 << 10
MAX_TEXT_LENGTH = 512
SYNC_KINDS = {"config", "reaction"}


class BadRequest(ValueError):
    """Client sent a body that does not match the endpoint schema."""


def parse_items(payload: Any) -> list[tuple[str, str]]:
    """Validate artist/title pairs for ``POST /embed``."""
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


def parse_texts(payload: Any) -> list[str]:
    """Validate free-text prompts for the semantic Go Beyond cards."""
    if not isinstance(payload, dict):
        raise BadRequest("body must be a JSON object")
    texts = payload.get("texts")
    if not isinstance(texts, list):
        raise BadRequest("'texts' must be a list")
    if len(texts) > MAX_TEXTS:
        raise BadRequest(f"too many texts (max {MAX_TEXTS})")
    if not texts:
        raise BadRequest("'texts' must not be empty")

    parsed: list[str] = []
    for index, text in enumerate(texts):
        if not isinstance(text, str) or not text.strip():
            raise BadRequest(f"texts[{index}] must be a non-empty string")
        if len(text) > MAX_TEXT_LENGTH:
            raise BadRequest(f"texts[{index}] is too long")
        parsed.append(text.strip())
    return parsed


def parse_sync_items(payload: Any) -> list[dict[str, Any]]:
    """Validate a batch of config/reaction sync items."""
    if not isinstance(payload, dict):
        raise BadRequest("body must be a JSON object")
    items = payload.get("items")
    if not isinstance(items, list):
        raise BadRequest("'items' must be a list")
    if len(items) > MAX_SYNC_ITEMS:
        raise BadRequest(f"too many items (max {MAX_SYNC_ITEMS})")

    parsed: list[dict[str, Any]] = []
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            raise BadRequest(f"items[{index}] must be an object")
        kind = item.get("kind")
        if kind not in SYNC_KINDS:
            raise BadRequest(f"items[{index}].kind is invalid")
        key = item.get("key")
        if not isinstance(key, str) or not key.strip():
            raise BadRequest(f"items[{index}].key must be a non-empty string")
        if len(key) > MAX_SYNC_KEY_LENGTH:
            raise BadRequest(f"items[{index}].key is too long")
        updated_at_ms = item.get("updated_at_ms")
        if isinstance(updated_at_ms, bool) or not isinstance(updated_at_ms, int):
            raise BadRequest(f"items[{index}].updated_at_ms must be an integer")
        if updated_at_ms < 0:
            raise BadRequest(f"items[{index}].updated_at_ms must be positive")
        deleted = item.get("deleted", False)
        if not isinstance(deleted, bool):
            raise BadRequest(f"items[{index}].deleted must be a boolean")
        raw_payload = item.get("payload", {})
        if isinstance(raw_payload, str):
            body = raw_payload
        else:
            body = json.dumps(raw_payload, separators=(",", ":"))
        if len(body.encode("utf-8")) > MAX_SYNC_PAYLOAD_BYTES:
            raise BadRequest(f"items[{index}].payload is too large")
        parsed.append(
            {
                "kind": kind,
                "key": key.strip(),
                "updated_at_ms": updated_at_ms,
                "deleted": deleted,
                "payload": body,
            }
        )
    return parsed


def parse_query_int(query: str, name: str, default: int) -> int:
    values = parse_qs(query).get(name)
    if not values:
        return default
    try:
        return int(values[0])
    except (TypeError, ValueError) as exc:
        raise BadRequest(f"{name} must be an integer") from exc
