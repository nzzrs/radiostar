"""Runtime configuration for the RadioStar server.

Every setting has a safe local default. Environment variables and command line
flags override the defaults, so the same package runs from a checkout, a venv
or a container without editing code.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any

SERVER_DIR = Path(__file__).resolve().parent
REPO_ROOT = SERVER_DIR.parent
DEFAULT_DB_PATH = SERVER_DIR / "data" / "auth.sqlite"
DEFAULT_CACHE_PATH = REPO_ROOT / "spike" / "clap" / "out" / "text_cache.sqlite"

AUTH_MODES = frozenset({"off", "optional", "required"})


class ConfigError(ValueError):
    """An environment variable or command line flag has an invalid value."""


def _text(env: Mapping[str, str], name: str, default: str) -> str:
    value = env.get(name, "").strip()
    return value or default


def _integer(
    env: Mapping[str, str],
    name: str,
    default: int,
    *,
    minimum: int,
    maximum: int | None = None,
) -> int:
    raw = env.get(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise ConfigError(f"{name} must be an integer, got {raw!r}") from exc
    if value < minimum or (maximum is not None and value > maximum):
        bounds = (
            f">= {minimum}" if maximum is None else f"between {minimum} and {maximum}"
        )
        raise ConfigError(f"{name} must be {bounds}, got {value}")
    return value


def _path(env: Mapping[str, str], name: str, default: Path) -> Path:
    raw = env.get(name, "").strip()
    return Path(raw).expanduser() if raw else default


def _optional_path(env: Mapping[str, str], name: str) -> Path | None:
    raw = env.get(name, "").strip()
    return Path(raw).expanduser() if raw else None


@dataclass(frozen=True)
class Settings:
    """Validated server settings."""

    host: str = "127.0.0.1"
    port: int = 8787
    auth_mode: str = "required"
    trust_proxy: str = ""
    max_threads: int = 16
    daily_items: int = 50000
    db_path: Path = DEFAULT_DB_PATH
    cache_path: Path = DEFAULT_CACHE_PATH
    checkpoint: Path | None = None

    def __post_init__(self) -> None:
        self._validate()

    def _validate(self) -> None:
        if self.auth_mode not in AUTH_MODES:
            modes = ", ".join(sorted(AUTH_MODES))
            raise ConfigError(
                f"RADIOSTAR_AUTH_MODE must be one of {modes}, got {self.auth_mode!r}"
            )
        if not isinstance(self.port, int) or isinstance(self.port, bool):
            raise ConfigError("port must be an integer")
        if not 0 <= self.port <= 65535:
            raise ConfigError(f"port must be between 0 and 65535, got {self.port}")
        if self.max_threads < 1:
            raise ConfigError("max_threads must be at least 1")
        if self.daily_items < 0:
            raise ConfigError("daily_items must be 0 or greater")

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Settings:
        """Build settings from ``RADIOSTAR_*`` variables.

        An empty variable means "use the default", so a copied example file with
        blank values still starts.
        """
        source: Mapping[str, str] = os.environ if env is None else env
        auth_mode = _text(source, "RADIOSTAR_AUTH_MODE", "required").lower()
        if auth_mode not in AUTH_MODES:
            modes = ", ".join(sorted(AUTH_MODES))
            raise ConfigError(
                f"RADIOSTAR_AUTH_MODE must be one of {modes}, got {auth_mode!r}"
            )
        return cls(
            host=_text(source, "RADIOSTAR_HOST", "127.0.0.1"),
            port=_integer(source, "RADIOSTAR_PORT", 8787, minimum=0, maximum=65535),
            auth_mode=auth_mode,
            trust_proxy=_text(source, "RADIOSTAR_TRUST_PROXY", "").lower(),
            max_threads=_integer(source, "RADIOSTAR_MAX_THREADS", 16, minimum=1),
            daily_items=_integer(
                source, "RADIOSTAR_EMBED_DAILY_ITEMS", 50000, minimum=0
            ),
            db_path=_path(source, "RADIOSTAR_DB_PATH", DEFAULT_DB_PATH),
            cache_path=_path(source, "RADIOSTAR_CACHE_PATH", DEFAULT_CACHE_PATH),
            checkpoint=_optional_path(source, "RADIOSTAR_CLAP_CHECKPOINT"),
        )

    def replace(self, **changes: Any) -> Settings:
        """Return a validated copy with selected fields changed."""
        return replace(self, **changes)
