"""SQLite persistence for RadioStar accounts, devices and tokens.

The store is intentionally small: one SQLite file, one connection guarded by a
lock, and only hashes for long-lived secrets. It is enough for one owner with
several devices and keeps the door open for a small group of users without
adding a database server.
"""

from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
import uuid
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 2

SCHEMA = """
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    disabled INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    revoked_at INTEGER
);

CREATE TABLE IF NOT EXISTS access_tokens (
    token_hash TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    token_hash TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    family_id TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at INTEGER,
    revoked_at INTEGER
);

CREATE TABLE IF NOT EXISTS pairing_codes (
    code_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL,
    used_at INTEGER,
    attempts INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sync_items (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    item_key TEXT NOT NULL,
    seq INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    device_id TEXT,
    deleted INTEGER NOT NULL DEFAULT 0,
    payload TEXT NOT NULL DEFAULT '{}',
    server_updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, kind, item_key)
);

CREATE TABLE IF NOT EXISTS sync_sequence (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    value INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);
CREATE INDEX IF NOT EXISTS idx_access_expiry ON access_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_refresh_device ON refresh_tokens(device_id);
CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_tokens(family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_expiry ON refresh_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_pairing_expiry ON pairing_codes(expires_at);
CREATE INDEX IF NOT EXISTS idx_sync_user_seq ON sync_items(user_id, seq);
"""


class AuthStore:
    """Thread-safe SQLite store. All timestamps are Unix seconds."""

    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        _chmod(self.path.parent, 0o700)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(self.path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.execute("PRAGMA foreign_keys=ON")
        self._db.execute("PRAGMA busy_timeout=5000")
        self._migrate()
        _chmod(self.path, 0o600)

    def _migrate(self) -> None:
        with self._lock, self._db:
            self._db.executescript(SCHEMA)
            self._db.execute("INSERT OR IGNORE INTO sync_sequence(id, value) VALUES (1, 0)")
            row = self._db.execute("SELECT version FROM schema_version LIMIT 1").fetchone()
            if row is None:
                self._db.execute("INSERT INTO schema_version(version) VALUES (?)", (SCHEMA_VERSION,))
            elif int(row["version"]) > SCHEMA_VERSION:
                raise RuntimeError(
                    f"unsupported auth schema {row['version']} (expected {SCHEMA_VERSION})"
                )
            elif int(row["version"]) < SCHEMA_VERSION:
                self._db.execute("UPDATE schema_version SET version = ?", (SCHEMA_VERSION,))

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # -- users ---------------------------------------------------------

    def create_user(self, username: str, password_hash: str, now: int | None = None) -> str:
        user_id = uuid.uuid4().hex
        now = int(time.time()) if now is None else now
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO users(id, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
                (user_id, username, password_hash, now),
            )
        return user_id

    def get_user_by_username(self, username: str) -> sqlite3.Row | None:
        with self._lock:
            return self._db.execute(
                "SELECT * FROM users WHERE username = ? COLLATE NOCASE",
                (username,),
            ).fetchone()

    def get_user(self, user_id: str) -> sqlite3.Row | None:
        with self._lock:
            return self._db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()

    def set_password(self, user_id: str, password_hash: str) -> None:
        with self._lock, self._db:
            self._db.execute(
                "UPDATE users SET password_hash = ? WHERE id = ?",
                (password_hash, user_id),
            )
            self._db.execute(
                "DELETE FROM access_tokens WHERE device_id IN "
                "(SELECT id FROM devices WHERE user_id = ?)",
                (user_id,),
            )
            self._db.execute(
                "DELETE FROM refresh_tokens WHERE device_id IN "
                "(SELECT id FROM devices WHERE user_id = ?)",
                (user_id,),
            )

    def user_count(self) -> int:
        with self._lock:
            row = self._db.execute("SELECT COUNT(*) AS n FROM users").fetchone()
            return int(row["n"])

    # -- devices -------------------------------------------------------

    def create_device(self, user_id: str, name: str, now: int | None = None) -> str:
        device_id = uuid.uuid4().hex
        now = int(time.time()) if now is None else now
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO devices(id, user_id, name, created_at, last_seen) "
                "VALUES (?, ?, ?, ?, ?)",
                (device_id, user_id, name[:120], now, now),
            )
        return device_id

    def get_device(self, device_id: str) -> sqlite3.Row | None:
        with self._lock:
            return self._db.execute(
                "SELECT * FROM devices WHERE id = ?",
                (device_id,),
            ).fetchone()

    def touch_device(self, device_id: str, now: int | None = None) -> None:
        now = int(time.time()) if now is None else now
        with self._lock, self._db:
            self._db.execute("UPDATE devices SET last_seen = ? WHERE id = ?", (now, device_id))

    def list_devices(self, user_id: str) -> list[sqlite3.Row]:
        with self._lock:
            return list(
                self._db.execute(
                    "SELECT id, name, created_at, last_seen, revoked_at FROM devices "
                    "WHERE user_id = ? ORDER BY created_at DESC",
                    (user_id,),
                ).fetchall()
            )

    def revoke_device(self, device_id: str, now: int | None = None) -> bool:
        now = int(time.time()) if now is None else now
        with self._lock, self._db:
            cursor = self._db.execute(
                "UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                (now, device_id),
            )
            self._db.execute("DELETE FROM access_tokens WHERE device_id = ?", (device_id,))
            self._db.execute("DELETE FROM refresh_tokens WHERE device_id = ?", (device_id,))
            return cursor.rowcount > 0

    # -- tokens --------------------------------------------------------

    def add_access_token(self, token_hash: str, device_id: str, expires_at: int) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO access_tokens(token_hash, device_id, expires_at) VALUES (?, ?, ?)",
                (token_hash, device_id, expires_at),
            )

    def authenticate_access(self, token_hash: str, now: int | None = None) -> sqlite3.Row | None:
        now = int(time.time()) if now is None else now
        with self._lock:
            return self._db.execute(
                """
                SELECT a.device_id, a.expires_at, d.user_id, d.name AS device_name,
                       u.username, u.disabled
                FROM access_tokens a
                JOIN devices d ON d.id = a.device_id
                JOIN users u ON u.id = d.user_id
                WHERE a.token_hash = ?
                  AND a.expires_at > ?
                  AND d.revoked_at IS NULL
                  AND u.disabled = 0
                """,
                (token_hash, now),
            ).fetchone()

    def add_refresh_token(
        self,
        token_hash: str,
        device_id: str,
        family_id: str,
        expires_at: int,
    ) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO refresh_tokens(token_hash, device_id, family_id, expires_at) "
                "VALUES (?, ?, ?, ?)",
                (token_hash, device_id, family_id, expires_at),
            )

    def find_refresh_token(self, token_hash: str) -> sqlite3.Row | None:
        with self._lock:
            return self._db.execute(
                """
                SELECT r.*, d.user_id, d.name AS device_name, d.revoked_at AS device_revoked,
                       u.username, u.disabled
                FROM refresh_tokens r
                JOIN devices d ON d.id = r.device_id
                JOIN users u ON u.id = d.user_id
                WHERE r.token_hash = ?
                """,
                (token_hash,),
            ).fetchone()

    def mark_refresh_used(self, token_hash: str, now: int | None = None) -> bool:
        now = int(time.time()) if now is None else now
        with self._lock, self._db:
            cursor = self._db.execute(
                "UPDATE refresh_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL",
                (now, token_hash),
            )
            return cursor.rowcount > 0

    def revoke_refresh_family(self, family_id: str, now: int | None = None) -> None:
        now = int(time.time()) if now is None else now
        with self._lock, self._db:
            self._db.execute(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ?",
                (now, family_id),
            )

    # -- pairing -------------------------------------------------------

    def add_pairing_code(
        self,
        code_hash: str,
        user_id: str,
        expires_at: int,
    ) -> None:
        with self._lock, self._db:
            # An owner only needs one active code at a time.
            self._db.execute(
                "DELETE FROM pairing_codes WHERE user_id = ? AND used_at IS NULL",
                (user_id,),
            )
            self._db.execute(
                "INSERT INTO pairing_codes(code_hash, user_id, expires_at) VALUES (?, ?, ?)",
                (code_hash, user_id, expires_at),
            )

    def consume_pairing_code(
        self,
        code_hash: str,
        now: int | None = None,
        max_attempts: int = 5,
    ) -> sqlite3.Row | None:
        now = int(time.time()) if now is None else now
        with self._lock, self._db:
            row = self._db.execute(
                "SELECT * FROM pairing_codes WHERE code_hash = ?",
                (code_hash,),
            ).fetchone()
            if row is None:
                return None
            if row["used_at"] is not None or row["expires_at"] <= now:
                return None
            if int(row["attempts"]) >= max_attempts:
                self._db.execute(
                    "UPDATE pairing_codes SET used_at = ? WHERE code_hash = ?",
                    (now, code_hash),
                )
                return None
            cursor = self._db.execute(
                "UPDATE pairing_codes SET used_at = ?, attempts = attempts + 1 "
                "WHERE code_hash = ? AND used_at IS NULL",
                (now, code_hash),
            )
            if cursor.rowcount == 0:
                return None
            return row

    def record_pairing_failure(self, code_hash: str, now: int | None = None) -> None:
        now = int(time.time()) if now is None else now
        with self._lock, self._db:
            self._db.execute(
                "UPDATE pairing_codes SET attempts = attempts + 1 WHERE code_hash = ?",
                (code_hash,),
            )
            self._db.execute(
                "UPDATE pairing_codes SET used_at = ? WHERE code_hash = ? AND attempts >= 5",
                (now, code_hash),
            )

    # -- sync ----------------------------------------------------------

    def pull_sync(
        self,
        user_id: str,
        since: int = 0,
        limit: int = 1000,
    ) -> dict[str, Any]:
        """Return the user's items changed after ``since``.

        Items keep a monotonic sequence, so a client can resume from the last
        sequence it applied. Tombstones are ordinary rows with ``deleted=1``.
        """
        limit = max(1, min(int(limit), 1000))
        with self._lock, self._db:
            rows = list(
                self._db.execute(
                    "SELECT kind, item_key, seq, updated_at_ms, device_id, deleted, payload "
                    "FROM sync_items WHERE user_id = ? AND seq > ? "
                    "ORDER BY seq LIMIT ?",
                    (user_id, max(0, int(since)), limit),
                ).fetchall()
            )
            high = self._db.execute(
                "SELECT value FROM sync_sequence WHERE id = 1"
            ).fetchone()
        high_seq = int(high["value"]) if high is not None else 0
        items = [_sync_row(row) for row in rows]
        has_more = len(items) >= limit
        next_since = int(items[-1]["seq"]) if has_more and items else high_seq
        return {"seq": high_seq, "next_since": next_since, "items": items, "has_more": has_more}

    def push_sync(
        self,
        user_id: str,
        items: list[dict[str, Any]],
        device_id: str | None = None,
    ) -> dict[str, Any]:
        """Apply client changes with last-writer-wins semantics.

        ``updated_at_ms`` is the client edit time. A lower (or equal tuple with
        a higher device id) value than the stored row loses and is returned as
        a conflict so the caller can adopt the server copy.
        """
        accepted: list[dict[str, Any]] = []
        conflicts: list[dict[str, Any]] = []
        now = int(time.time())
        with self._lock, self._db:
            row = self._db.execute("SELECT value FROM sync_sequence WHERE id = 1").fetchone()
            seq = int(row["value"]) if row is not None else 0
            for item in items:
                kind = str(item["kind"])
                key = str(item["key"])
                updated_at_ms = int(item["updated_at_ms"])
                deleted = 1 if item.get("deleted") else 0
                payload = str(item.get("payload") or "{}")
                existing = self._db.execute(
                    "SELECT kind, item_key, seq, updated_at_ms, device_id, deleted, payload "
                    "FROM sync_items WHERE user_id = ? AND kind = ? AND item_key = ?",
                    (user_id, kind, key),
                ).fetchone()
                if existing is not None:
                    existing_tuple = (int(existing["updated_at_ms"]), existing["device_id"] or "")
                    incoming_tuple = (updated_at_ms, device_id or "")
                    if incoming_tuple < existing_tuple:
                        conflicts.append(_sync_row(existing))
                        continue
                seq += 1
                self._db.execute(
                    "INSERT INTO sync_items"
                    "(user_id, kind, item_key, seq, updated_at_ms, device_id, deleted, payload, server_updated_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(user_id, kind, item_key) DO UPDATE SET "
                    "seq = excluded.seq, updated_at_ms = excluded.updated_at_ms, "
                    "device_id = excluded.device_id, deleted = excluded.deleted, "
                    "payload = excluded.payload, server_updated_at = excluded.server_updated_at",
                    (user_id, kind, key, seq, updated_at_ms, device_id, deleted, payload, now),
                )
                accepted.append({"kind": kind, "key": key, "seq": seq})
            self._db.execute("UPDATE sync_sequence SET value = ? WHERE id = 1", (seq,))
        return {"seq": seq, "accepted": accepted, "conflicts": conflicts}

    def wipe_sync(self, user_id: str) -> None:
        with self._lock, self._db:
            self._db.execute("DELETE FROM sync_items WHERE user_id = ?", (user_id,))

    def sync_status(self, user_id: str) -> dict[str, Any]:
        with self._lock, self._db:
            rows = self._db.execute(
                "SELECT kind, COUNT(*) AS n, MAX(seq) AS last FROM sync_items "
                "WHERE user_id = ? GROUP BY kind",
                (user_id,),
            ).fetchall()
            high = self._db.execute("SELECT value FROM sync_sequence WHERE id = 1").fetchone()
        counts = {row["kind"]: int(row["n"]) for row in rows}
        return {
            "seq": int(high["value"]) if high is not None else 0,
            "config_count": counts.get("config", 0),
            "reaction_count": counts.get("reaction", 0),
        }

    # -- maintenance ---------------------------------------------------

    def cleanup(self, now: int | None = None) -> None:
        now = int(time.time()) if now is None else now
        with self._lock, self._db:
            self._db.execute("DELETE FROM access_tokens WHERE expires_at <= ?", (now,))
            self._db.execute("DELETE FROM refresh_tokens WHERE expires_at <= ?", (now,))
            self._db.execute(
                "DELETE FROM pairing_codes WHERE expires_at <= ? OR used_at IS NOT NULL",
                (now,),
            )


def _sync_row(row: sqlite3.Row) -> dict[str, Any]:
    try:
        payload = json.loads(row["payload"])
    except (TypeError, ValueError):
        payload = row["payload"]
    return {
        "kind": row["kind"],
        "key": row["item_key"],
        "seq": int(row["seq"]),
        "updated_at_ms": int(row["updated_at_ms"]),
        "device_id": row["device_id"],
        "deleted": bool(row["deleted"]),
        "payload": payload,
    }


def _chmod(path: Path, mode: int) -> None:
    try:
        os.chmod(path, mode)
    except OSError:
        pass
