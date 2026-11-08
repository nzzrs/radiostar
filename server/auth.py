#!/usr/bin/env python3
"""Accounts, device tokens, pairing codes and request limits.

Access tokens are opaque and short lived. Refresh tokens rotate and are stored
only as SHA-256 hashes. The module keeps a small in-memory cache of the last
refresh response so a lost network reply does not revoke a healthy device.
"""

from __future__ import annotations

import hashlib
import secrets
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Any

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError

try:  # package import (`python -m server.app`) and script import both work
    from .auth_store import AuthStore
except ImportError:  # pragma: no cover
    from auth_store import AuthStore  # type: ignore

ACCESS_TTL_SECONDS = 15 * 60
REFRESH_TTL_SECONDS = 30 * 24 * 60 * 60
PAIRING_TTL_SECONDS = 5 * 60
PAIRING_ATTEMPTS = 5
PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
PAIRING_LENGTH = 10
REFRESH_GRACE_SECONDS = 120
MIN_PASSWORD_LENGTH = 12

_PASSWORD_HASHER = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=1)
_PASSWORD_SLOTS = threading.BoundedSemaphore(2)
# A fixed hash used only to equalise the failed-login path.
_DUMMY_HASH = _PASSWORD_HASHER.hash("not-a-real-password")


class AuthError(Exception):
    """Base class for expected authentication failures."""


class InvalidCredentials(AuthError):
    """Wrong username/password or malformed token."""


class InvalidPairingCode(AuthError):
    """Expired, used or unknown pairing code."""


class PasswordPolicyError(AuthError):
    """Password does not meet the minimum policy."""


@dataclass(frozen=True)
class Session:
    user_id: str
    username: str
    device_id: str
    device_name: str


@dataclass(frozen=True)
class IssuedTokens:
    access_token: str
    refresh_token: str
    expires_in: int
    device_id: str
    device_name: str
    username: str

    def as_dict(self) -> dict[str, Any]:
        return {
            "access_token": self.access_token,
            "refresh_token": self.refresh_token,
            "token_type": "Bearer",
            "expires_in": self.expires_in,
            "device_id": self.device_id,
            "device_name": self.device_name,
            "username": self.username,
        }


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def new_token() -> str:
    return secrets.token_urlsafe(32)


def new_pairing_code() -> str:
    raw = "".join(secrets.choice(PAIRING_ALPHABET) for _ in range(PAIRING_LENGTH))
    return f"{raw[:5]}-{raw[5:]}"


def normalize_pairing_code(code: str) -> str:
    return "".join(ch for ch in code.upper() if ch.isalnum())


def hash_password(password: str) -> str:
    if len(password) < MIN_PASSWORD_LENGTH:
        raise PasswordPolicyError(f"password must be at least {MIN_PASSWORD_LENGTH} characters")
    with _PASSWORD_SLOTS:
        return _PASSWORD_HASHER.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    try:
        with _PASSWORD_SLOTS:
            return _PASSWORD_HASHER.verify(password_hash, password)
    except (VerificationError, InvalidHashError):
        return False


def _constant_time_failure() -> None:
    # Keep the wrong-username path as expensive as a wrong password.
    try:
        with _PASSWORD_SLOTS:
            _PASSWORD_HASHER.verify(_DUMMY_HASH, "wrong-password")
    except VerificationError:
        pass


class TokenService:
    def __init__(self, store: AuthStore) -> None:
        self._store = store
        self._lock = threading.RLock()
        # token hash -> (expires_at, IssuedTokens)
        self._refresh_cache: dict[str, tuple[int, IssuedTokens]] = {}
        self._last_cleanup = 0.0

    # -- login and pairing --------------------------------------------

    def login(self, username: str, password: str, device_name: str) -> IssuedTokens:
        user = self._store.get_user_by_username(username.strip())
        if user is None or int(user["disabled"]) != 0:
            _constant_time_failure()
            raise InvalidCredentials("invalid credentials")
        if not verify_password(password, user["password_hash"]):
            raise InvalidCredentials("invalid credentials")

        device_id = self._store.create_device(user["id"], _clean_device_name(device_name))
        return self._issue(user["id"], user["username"], device_id, _clean_device_name(device_name))

    def pair(self, code: str, device_name: str) -> IssuedTokens:
        normalized = normalize_pairing_code(code)
        if not normalized:
            raise InvalidPairingCode("invalid pairing code")
        code_hash = hash_token(normalized)
        row = self._store.consume_pairing_code(code_hash, max_attempts=PAIRING_ATTEMPTS)
        if row is None:
            self._store.record_pairing_failure(code_hash)
            raise InvalidPairingCode("invalid pairing code")
        user = self._store.get_user(row["user_id"])
        if user is None or int(user["disabled"]) != 0:
            raise InvalidPairingCode("invalid pairing code")
        name = _clean_device_name(device_name)
        device_id = self._store.create_device(user["id"], name)
        return self._issue(user["id"], user["username"], device_id, name)

    def create_pairing_code(self, user_id: str) -> tuple[str, int]:
        code = new_pairing_code()
        expires_at = int(time.time()) + PAIRING_TTL_SECONDS
        self._store.add_pairing_code(hash_token(normalize_pairing_code(code)), user_id, expires_at)
        return code, expires_at

    # -- token lifecycle ----------------------------------------------

    def _issue(
        self,
        user_id: str,
        username: str,
        device_id: str,
        device_name: str,
        family_id: str | None = None,
    ) -> IssuedTokens:
        now = int(time.time())
        access_token = new_token()
        refresh_token = new_token()
        family = family_id or uuid.uuid4().hex
        self._store.add_access_token(hash_token(access_token), device_id, now + ACCESS_TTL_SECONDS)
        self._store.add_refresh_token(
            hash_token(refresh_token),
            device_id,
            family,
            now + REFRESH_TTL_SECONDS,
        )
        self._maybe_cleanup(now)
        return IssuedTokens(
            access_token=access_token,
            refresh_token=refresh_token,
            expires_in=ACCESS_TTL_SECONDS,
            device_id=device_id,
            device_name=device_name,
            username=username,
        )

    def refresh(self, refresh_token: str) -> IssuedTokens:
        if not refresh_token:
            raise InvalidCredentials("invalid refresh token")
        token_hash = hash_token(refresh_token)
        now = int(time.time())
        with self._lock:
            cached = self._refresh_cache.get(token_hash)
            if cached is not None:
                expires_at, issued = cached
                row = self._store.find_refresh_token(token_hash)
                if (
                    expires_at > now
                    and row is not None
                    and row["revoked_at"] is None
                    and row["device_revoked"] is None
                    and int(row["disabled"]) == 0
                ):
                    return issued
                self._refresh_cache.pop(token_hash, None)
                raise InvalidCredentials("invalid refresh token")

            row = self._store.find_refresh_token(token_hash)
            if row is None:
                raise InvalidCredentials("invalid refresh token")
            if (
                row["revoked_at"] is not None
                or row["device_revoked"] is not None
                or int(row["disabled"]) != 0
                or int(row["expires_at"]) <= now
            ):
                raise InvalidCredentials("invalid refresh token")

            if row["used_at"] is not None:
                # A retry inside the grace window can use the cached response.
                self._store.revoke_refresh_family(row["family_id"], now)
                raise InvalidCredentials("refresh token reused")

            if not self._store.mark_refresh_used(token_hash, now):
                raise InvalidCredentials("refresh token reused")

            issued = self._issue(
                row["user_id"],
                row["username"],
                row["device_id"],
                row["device_name"],
                family_id=row["family_id"],
            )
            self._refresh_cache[token_hash] = (now + REFRESH_GRACE_SECONDS, issued)
            self._prune_refresh_cache(now)
            return issued

    def authenticate(self, access_token: str) -> Session | None:
        if not access_token:
            return None
        row = self._store.authenticate_access(hash_token(access_token))
        if row is None:
            return None
        self._store.touch_device(row["device_id"])
        return Session(
            user_id=row["user_id"],
            username=row["username"],
            device_id=row["device_id"],
            device_name=row["device_name"],
        )

    def logout(self, device_id: str) -> None:
        self._store.revoke_device(device_id)
        with self._lock:
            for key, (_, issued) in list(self._refresh_cache.items()):
                if issued.device_id == device_id:
                    self._refresh_cache.pop(key, None)

    def list_devices(self, user_id: str, current_device_id: str) -> list[dict[str, Any]]:
        return [
            {
                "id": row["id"],
                "name": row["name"],
                "created_at": row["created_at"],
                "last_seen": row["last_seen"],
                "revoked": row["revoked_at"] is not None,
                "current": row["id"] == current_device_id,
            }
            for row in self._store.list_devices(user_id)
        ]

    def revoke_device(self, user_id: str, device_id: str) -> bool:
        device = self._store.get_device(device_id)
        if device is None or device["user_id"] != user_id:
            return False
        revoked = self._store.revoke_device(device_id)
        with self._lock:
            for key, (_, issued) in list(self._refresh_cache.items()):
                if issued.device_id == device_id:
                    self._refresh_cache.pop(key, None)
        return revoked

    # -- maintenance ---------------------------------------------------

    def _prune_refresh_cache(self, now: int) -> None:
        if len(self._refresh_cache) < 256:
            return
        for key, (expires_at, _) in list(self._refresh_cache.items()):
            if expires_at <= now:
                self._refresh_cache.pop(key, None)

    def _maybe_cleanup(self, now: int) -> None:
        if now - self._last_cleanup < 3600:
            return
        self._last_cleanup = now
        self._store.cleanup(now)


class RateLimiter:
    """Small fixed-window limiter keyed by action and identity."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._hits: dict[str, list[float]] = {}

    def allow(self, key: str, limit: int, window_seconds: int) -> bool:
        now = time.monotonic()
        cutoff = now - window_seconds
        with self._lock:
            hits = [hit for hit in self._hits.get(key, []) if hit > cutoff]
            if len(hits) >= limit:
                self._hits[key] = hits
                return False
            hits.append(now)
            self._hits[key] = hits
            if len(self._hits) > 4096:
                self._prune(cutoff)
            return True

    def _prune(self, cutoff: float) -> None:
        for key in list(self._hits):
            hits = [hit for hit in self._hits[key] if hit > cutoff]
            if hits:
                self._hits[key] = hits
            else:
                self._hits.pop(key, None)


class DailyQuota:
    """Per-user daily item counter; bounds a leaked token."""

    def __init__(self, limit: int) -> None:
        self._limit = limit
        self._lock = threading.Lock()
        self._counts: dict[tuple[str, str], int] = {}

    def take(self, identity: str, amount: int, day: str) -> bool:
        if self._limit <= 0:
            return True
        key = (identity, day)
        with self._lock:
            current = self._counts.get(key, 0)
            if current + amount > self._limit:
                return False
            self._counts[key] = current + amount
            if len(self._counts) > 4096:
                for old_key in [item for item in self._counts if item[1] != day]:
                    self._counts.pop(old_key, None)
            return True


def _clean_device_name(name: str) -> str:
    cleaned = " ".join(str(name or "").split())
    return cleaned[:80] or "Unknown device"
