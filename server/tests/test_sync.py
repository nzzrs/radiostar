from __future__ import annotations

import http.client
import json
import threading

import numpy as np
import pytest

from server.app import build_context, create_server
from server.auth import hash_password


class FakeEmbedder:
    def embed(self, artist: str, title: str):
        return np.array([1.0, 0.0, 0.0], dtype=np.float32)


@pytest.fixture()
def http_server(tmp_path):
    context = build_context(FakeEmbedder(), tmp_path / "auth.sqlite")
    context.store.create_user("owner", hash_password("correct horse battery staple"))
    context.store.create_user("other", hash_password("another correct horse battery"))
    owner = context.tokens.login("owner", "correct horse battery staple", "Phone")
    other = context.tokens.login("other", "another correct horse battery", "Tablet")
    server = create_server(context, "127.0.0.1", 0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address
    yield host, port, owner, other, context
    server.shutdown()
    server.server_close()
    context.store.close()


def request(host, port, method, path, body=None, token=None):
    connection = http.client.HTTPConnection(host, port, timeout=5)
    headers = {}
    payload = None
    if body is not None:
        payload = json.dumps(body)
        headers["content-type"] = "application/json"
    if token:
        headers["authorization"] = f"Bearer {token}"
    connection.request(method, path, body=payload, headers=headers)
    response = connection.getresponse()
    raw = response.read()
    connection.close()
    return response, json.loads(raw) if raw else None


def config_item(key="theme", value="dark", updated_at_ms=1000, deleted=False):
    return {
        "kind": "config",
        "key": key,
        "updated_at_ms": updated_at_ms,
        "deleted": deleted,
        "payload": {"type": "string", "value": value},
    }


def reaction_item(key="video-1", rating=1, updated_at_ms=2000, deleted=False):
    return {
        "kind": "reaction",
        "key": key,
        "updated_at_ms": updated_at_ms,
        "deleted": deleted,
        "payload": {"rating": rating, "title": "Song", "artist": "Artist"},
    }


def test_sync_requires_auth(http_server):
    host, port, _, _, _ = http_server
    response, body = request(host, port, "GET", "/sync/pull")
    assert response.status == 401
    assert body == {"error": "unauthorized"}

    response, _ = request(host, port, "POST", "/sync/push", {"items": [config_item()]})
    assert response.status == 401


def test_push_and_pull_roundtrip(http_server):
    host, port, owner, _, _ = http_server
    response, body = request(
        host,
        port,
        "POST",
        "/sync/push",
        {"items": [config_item(), reaction_item()]},
        token=owner.access_token,
    )
    assert response.status == 200
    assert len(body["accepted"]) == 2
    assert body["conflicts"] == []

    response, body = request(
        host, port, "GET", "/sync/pull?since=0", token=owner.access_token
    )
    assert response.status == 200
    assert body["has_more"] is False
    items = {(item["kind"], item["key"]): item for item in body["items"]}
    assert items[("config", "theme")]["payload"]["value"] == "dark"
    assert items[("reaction", "video-1")]["payload"]["rating"] == 1
    assert body["next_since"] == max(item["seq"] for item in body["items"])


def test_pull_is_incremental(http_server):
    host, port, owner, _, _ = http_server
    request(
        host,
        port,
        "POST",
        "/sync/push",
        {"items": [config_item(key="a")]},
        token=owner.access_token,
    )
    _, first = request(host, port, "GET", "/sync/pull?since=0", token=owner.access_token)
    cursor = first["next_since"]

    request(
        host,
        port,
        "POST",
        "/sync/push",
        {"items": [config_item(key="b", updated_at_ms=2000)]},
        token=owner.access_token,
    )
    _, second = request(
        host, port, "GET", f"/sync/pull?since={cursor}", token=owner.access_token
    )
    assert [item["key"] for item in second["items"]] == ["b"]


def test_last_writer_wins_and_returns_conflict(http_server):
    host, port, owner, _, _ = http_server
    request(
        host,
        port,
        "POST",
        "/sync/push",
        {"items": [config_item(value="dark", updated_at_ms=5000)]},
        token=owner.access_token,
    )
    response, body = request(
        host,
        port,
        "POST",
        "/sync/push",
        {"items": [config_item(value="light", updated_at_ms=4000)]},
        token=owner.access_token,
    )
    assert response.status == 200
    assert body["accepted"] == []
    assert body["conflicts"][0]["payload"]["value"] == "dark"

    _, pulled = request(host, port, "GET", "/sync/pull?since=0", token=owner.access_token)
    assert pulled["items"][0]["payload"]["value"] == "dark"


def test_tombstone_is_delivered(http_server):
    host, port, owner, _, _ = http_server
    request(
        host,
        port,
        "POST",
        "/sync/push",
        {"items": [reaction_item()]},
        token=owner.access_token,
    )
    request(
        host,
        port,
        "POST",
        "/sync/push",
        {"items": [reaction_item(updated_at_ms=3000, deleted=True)]},
        token=owner.access_token,
    )
    _, body = request(host, port, "GET", "/sync/pull?since=0", token=owner.access_token)
    assert body["items"][0]["deleted"] is True


def test_users_are_isolated(http_server):
    host, port, owner, other, _ = http_server
    request(
        host,
        port,
        "POST",
        "/sync/push",
        {"items": [config_item(key="owner-key")]},
        token=owner.access_token,
    )
    response, body = request(host, port, "GET", "/sync/pull?since=0", token=other.access_token)
    assert response.status == 200
    assert body["items"] == []


def test_validation_rejects_bad_items(http_server):
    host, port, owner, _, _ = http_server
    for item in (
        {"kind": "other", "key": "x", "updated_at_ms": 1, "payload": {}},
        {"kind": "config", "key": "", "updated_at_ms": 1, "payload": {}},
        {"kind": "config", "key": "x", "updated_at_ms": -1, "payload": {}},
        {"kind": "config", "key": "x", "updated_at_ms": 1, "payload": {}, "deleted": "no"},
    ):
        response, body = request(
            host, port, "POST", "/sync/push", {"items": [item]}, token=owner.access_token
        )
        assert response.status == 400, body


def test_status_and_wipe(http_server):
    host, port, owner, _, _ = http_server
    request(
        host,
        port,
        "POST",
        "/sync/push",
        {"items": [config_item(), reaction_item()]},
        token=owner.access_token,
    )
    response, body = request(host, port, "GET", "/sync/status", token=owner.access_token)
    assert response.status == 200
    assert body["config_count"] == 1
    assert body["reaction_count"] == 1

    response, body = request(host, port, "DELETE", "/sync", token=owner.access_token)
    assert response.status == 200
    assert body == {"ok": True}

    _, body = request(host, port, "GET", "/sync/status", token=owner.access_token)
    assert body["config_count"] == 0
    assert body["reaction_count"] == 0
