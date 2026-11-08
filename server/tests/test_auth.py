from __future__ import annotations

import time

import pytest

from server.auth import (
    InvalidCredentials,
    InvalidPairingCode,
    PasswordPolicyError,
    RateLimiter,
    TokenService,
    hash_password,
    hash_token,
    new_pairing_code,
    normalize_pairing_code,
)
from server.auth_store import AuthStore

PASSWORD = "correct horse battery staple"


@pytest.fixture()
def store(tmp_path):
    db = AuthStore(tmp_path / "auth.sqlite")
    yield db
    db.close()


@pytest.fixture()
def service(store):
    user_id = store.create_user("owner", hash_password(PASSWORD))
    return TokenService(store), user_id


def test_schema_v1_migrates_to_sync_tables(tmp_path):
    import sqlite3

    path = tmp_path / "old.sqlite"
    connection = sqlite3.connect(path)
    connection.executescript(
        "CREATE TABLE schema_version (version INTEGER NOT NULL);"
        "INSERT INTO schema_version(version) VALUES (1);"
    )
    connection.close()

    store = AuthStore(path)
    try:
        version = store._db.execute("SELECT version FROM schema_version").fetchone()["version"]
        tables = {
            row["name"]
            for row in store._db.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        assert version == 2
        assert {"sync_items", "sync_sequence"} <= tables
    finally:
        store.close()


def test_login_issues_working_access_and_refresh(service):
    tokens, _ = service
    issued = tokens.login("owner", PASSWORD, "Pixel")
    assert issued.access_token
    assert issued.refresh_token
    session = tokens.authenticate(issued.access_token)
    assert session is not None
    assert session.username == "owner"
    assert session.device_name == "Pixel"


def test_wrong_password_is_rejected(service):
    tokens, _ = service
    with pytest.raises(InvalidCredentials):
        tokens.login("owner", "wrong password", "Pixel")
    with pytest.raises(InvalidCredentials):
        tokens.login("missing", PASSWORD, "Pixel")


def test_password_policy(store):
    with pytest.raises(PasswordPolicyError):
        store.create_user("short", hash_password("too short"))


def test_refresh_rotates_and_reuse_revokes_family(service):
    tokens, _ = service
    issued = tokens.login("owner", PASSWORD, "Pixel")
    old_refresh = issued.refresh_token

    first = tokens.refresh(old_refresh)
    assert first.refresh_token != old_refresh

    # A lost response may retry once inside the grace window.
    retry = tokens.refresh(old_refresh)
    assert retry.refresh_token == first.refresh_token

    # Outside the grace window, reuse is treated as theft.
    tokens._refresh_cache.clear()
    with pytest.raises(InvalidCredentials):
        tokens.refresh(old_refresh)
    with pytest.raises(InvalidCredentials):
        tokens.refresh(first.refresh_token)


def test_logout_revokes_access_and_refresh(service):
    tokens, _ = service
    issued = tokens.login("owner", PASSWORD, "Pixel")
    tokens.logout(issued.device_id)
    assert tokens.authenticate(issued.access_token) is None
    with pytest.raises(InvalidCredentials):
        tokens.refresh(issued.refresh_token)


def test_pairing_code_is_single_use(store):
    user_id = store.create_user("owner", hash_password(PASSWORD))
    tokens = TokenService(store)
    code, _ = tokens.create_pairing_code(user_id)
    issued = tokens.pair(code, "Tablet")
    assert issued.access_token
    with pytest.raises(InvalidPairingCode):
        tokens.pair(code, "Other")


def test_pairing_code_normalizes_spaces_and_dashes(store):
    user_id = store.create_user("owner", hash_password(PASSWORD))
    tokens = TokenService(store)
    code, _ = tokens.create_pairing_code(user_id)
    spaced = f"  {code.lower().replace('-', ' ')}  "
    assert normalize_pairing_code(spaced) == normalize_pairing_code(code)
    issued = tokens.pair(spaced, "Tablet")
    assert issued.device_name == "Tablet"


def test_expired_refresh_is_rejected(store):
    store.create_user("owner", hash_password(PASSWORD))
    tokens = TokenService(store)
    issued = tokens.login("owner", PASSWORD, "Pixel")
    with store._lock, store._db:
        store._db.execute(
            "UPDATE refresh_tokens SET expires_at = ? WHERE token_hash = ?",
            (int(time.time()) - 1, hash_token(issued.refresh_token)),
        )
    with pytest.raises(InvalidCredentials):
        tokens.refresh(issued.refresh_token)


def test_set_password_revokes_tokens(store):
    user_id = store.create_user("owner", hash_password(PASSWORD))
    tokens = TokenService(store)
    issued = tokens.login("owner", PASSWORD, "Pixel")
    store.set_password(user_id, hash_password("a different long password"))
    assert tokens.authenticate(issued.access_token) is None
    with pytest.raises(InvalidCredentials):
        tokens.refresh(issued.refresh_token)


def test_password_change_invalidates_refresh_grace(service):
    tokens, user_id = service
    issued = tokens.login("owner", PASSWORD, "Pixel")
    tokens.refresh(issued.refresh_token)
    # The old token has a cached response; revocation must still win.
    store = tokens._store
    store.set_password(user_id, hash_password("a different long password"))
    with pytest.raises(InvalidCredentials):
        tokens.refresh(issued.refresh_token)


def test_rate_limiter():
    limiter = RateLimiter()
    assert limiter.allow("k", 2, 60)
    assert limiter.allow("k", 2, 60)
    assert not limiter.allow("k", 2, 60)


def test_pairing_code_alphabet():
    code = new_pairing_code()
    normalized = normalize_pairing_code(code)
    assert len(normalized) == 10
    assert all(ch.isalnum() for ch in normalized)
