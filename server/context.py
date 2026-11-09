"""Application state shared by every request handler."""

from __future__ import annotations

import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .auth import DailyQuota, RateLimiter, TokenService
from .auth_store import AuthStore
from .config import Settings


@dataclass
class AppContext:
    store: AuthStore
    tokens: TokenService
    limiter: RateLimiter
    quota: DailyQuota
    embedder: Any
    embed_lock: threading.Lock
    auth_mode: str = "required"
    trust_proxy: str = ""
    settings: Settings | None = None


def build_context(
    embedder: Any,
    db_path: Path | str | None = None,
    *,
    settings: Settings | None = None,
) -> AppContext:
    """Create the application state used by the HTTP server and tests."""
    settings = Settings.from_env() if settings is None else settings
    if db_path is not None:
        settings = settings.replace(db_path=Path(db_path))
    store = AuthStore(settings.db_path)
    return AppContext(
        store=store,
        tokens=TokenService(store),
        limiter=RateLimiter(),
        quota=DailyQuota(settings.daily_items),
        embedder=embedder,
        embed_lock=threading.Lock(),
        auth_mode=settings.auth_mode,
        trust_proxy=settings.trust_proxy,
        settings=settings,
    )
