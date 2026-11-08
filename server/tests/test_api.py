from __future__ import annotations

import http.client
import json
import threading

import numpy as np
import pytest

from server.app import DailyQuota, build_context, create_server
from server.auth import hash_password


class FakeEmbedder:
    def embed(self, artist: str, title: str):
        return np.array([1.0, 0.0, 0.0], dtype=np.float32)


@pytest.fixture()
def http_server(tmp_path):
    context = build_context(FakeEmbedder(), tmp_path / "auth.sqlite")
    context.store.create_user("owner", hash_password("correct horse battery staple"))
    issued = context.tokens.login("owner", "correct horse battery staple", "Test client")
    server = create_server(context, "127.0.0.1", 0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address
    yield host, port, issued, context
    server.shutdown()
    server.server_close()
    context.store.close()


def request(host, port, method, path, body=None, token=None, headers=None):
    connection = http.client.HTTPConnection(host, port, timeout=5)
    request_headers = dict(headers or {})
    payload = None
    if body is not None:
        payload = json.dumps(body)
        request_headers["content-type"] = "application/json"
    if token:
        request_headers["authorization"] = f"Bearer {token}"
    connection.request(method, path, body=payload, headers=request_headers)
    response = connection.getresponse()
    raw = response.read()
    connection.close()
    return response, json.loads(raw) if raw else None


def test_health_is_minimal(http_server):
    host, port, _, _ = http_server
    response, body = request(host, port, "GET", "/health")
    assert response.status == 200
    assert body == {"ok": True}
    assert response.version == 11
    assert "RadioStar" in response.getheader("server", "")


def test_embed_requires_bearer(http_server):
    host, port, issued, _ = http_server
    response, body = request(
        host,
        port,
        "POST",
        "/embed",
        {"items": [{"artist": "A", "title": "B"}]},
    )
    assert response.status == 401
    assert body == {"error": "unauthorized"}
    assert response.getheader("www-authenticate").startswith("Bearer")

    response, body = request(
        host,
        port,
        "POST",
        "/embed",
        {"items": [{"artist": "A", "title": "B"}]},
        token=issued.access_token,
    )
    assert response.status == 200
    assert body["vectors"] == [[1.0, 0.0, 0.0]]


def test_login_and_me(http_server):
    host, port, _, _ = http_server
    response, body = request(
        host,
        port,
        "POST",
        "/auth/login",
        {
            "username": "owner",
            "password": "correct horse battery staple",
            "device_name": "Phone",
        },
    )
    assert response.status == 200
    assert body["token_type"] == "Bearer"
    assert body["expires_in"] == 900

    me, me_body = request(
        host,
        port,
        "GET",
        "/auth/me",
        token=body["access_token"],
    )
    assert me.status == 200
    assert me_body["username"] == "owner"
    assert me_body["device_name"] == "Phone"


def test_bad_login_is_generic(http_server):
    host, port, _, _ = http_server
    response, body = request(
        host,
        port,
        "POST",
        "/auth/login",
        {"username": "owner", "password": "wrong password", "device_name": "Phone"},
    )
    assert response.status == 401
    assert body == {"error": "invalid credentials"}


def test_logout_revokes_token(http_server):
    host, port, issued, _ = http_server
    response, _ = request(host, port, "POST", "/auth/logout", token=issued.access_token)
    assert response.status == 200
    response, _ = request(
        host,
        port,
        "POST",
        "/embed",
        {"items": [{"artist": "A", "title": "B"}]},
        token=issued.access_token,
    )
    assert response.status == 401


def test_pairing_code_endpoint(http_server):
    host, port, issued, _ = http_server
    response, body = request(host, port, "POST", "/auth/pairing-code", token=issued.access_token)
    assert response.status == 200
    assert body["code"]
    assert body["expires_in"] > 0

    response, paired = request(
        host,
        port,
        "POST",
        "/auth/pair",
        {"code": body["code"], "device_name": "Tablet"},
    )
    assert response.status == 200
    assert paired["device_name"] == "Tablet"


def test_devices_list_and_revoke(http_server):
    host, port, issued, _ = http_server
    response, body = request(host, port, "GET", "/auth/devices", token=issued.access_token)
    assert response.status == 200
    assert len(body["devices"]) == 1

    response, _ = request(
        host,
        port,
        "DELETE",
        f"/auth/devices/{issued.device_id}",
        token=issued.access_token,
    )
    assert response.status == 200
    response, _ = request(
        host,
        port,
        "GET",
        "/auth/me",
        token=issued.access_token,
    )
    assert response.status == 401


def test_embed_validates_items(http_server):
    host, port, issued, _ = http_server
    response, body = request(
        host,
        port,
        "POST",
        "/embed",
        {"items": [{"artist": "", "title": "B"}]},
        token=issued.access_token,
    )
    assert response.status == 400
    assert "artist" in body["error"]


def test_embed_rejects_too_many_items(http_server):
    host, port, issued, _ = http_server
    items = [{"artist": "A", "title": str(index)} for index in range(257)]
    response, body = request(host, port, "POST", "/embed", {"items": items}, token=issued.access_token)
    assert response.status == 400
    assert "too many" in body["error"]


def test_embed_quota(http_server):
    host, port, issued, context = http_server
    context.quota = DailyQuota(1)
    response, _ = request(
        host,
        port,
        "POST",
        "/embed",
        {"items": [{"artist": "A", "title": "B"}]},
        token=issued.access_token,
    )
    assert response.status == 200
    response, body = request(
        host,
        port,
        "POST",
        "/embed",
        {"items": [{"artist": "A", "title": "C"}]},
        token=issued.access_token,
    )
    assert response.status == 429
    assert "quota" in body["error"]


def test_unknown_path_is_404(http_server):
    host, port, _, _ = http_server
    response, body = request(host, port, "GET", "/nope")
    assert response.status == 404
    assert body == {"error": "not found"}
