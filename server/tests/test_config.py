from __future__ import annotations

from pathlib import Path

import pytest

from server.config import (
    DEFAULT_CACHE_PATH,
    DEFAULT_DB_PATH,
    SERVER_DIR,
    ConfigError,
    Settings,
)


def test_defaults_are_local_and_safe():
    settings = Settings.from_env({})

    assert settings.host == "127.0.0.1"
    assert settings.port == 8787
    assert settings.auth_mode == "required"
    assert settings.trust_proxy == ""
    assert settings.max_threads == 16
    assert settings.daily_items == 50000
    assert settings.db_path == DEFAULT_DB_PATH
    assert settings.cache_path == DEFAULT_CACHE_PATH
    assert settings.checkpoint is None


def test_cache_defaults_next_to_server_data():
    settings = Settings.from_env({})

    assert settings.cache_path == SERVER_DIR / "data" / "text_cache.sqlite"
    assert "spike" not in settings.cache_path.parts


def test_environment_overrides_every_setting(tmp_path: Path):
    env = {
        "RADIOSTAR_HOST": "0.0.0.0",
        "RADIOSTAR_PORT": "9000",
        "RADIOSTAR_AUTH_MODE": "optional",
        "RADIOSTAR_TRUST_PROXY": "cloudflare",
        "RADIOSTAR_MAX_THREADS": "4",
        "RADIOSTAR_EMBED_DAILY_ITEMS": "10",
        "RADIOSTAR_DB_PATH": str(tmp_path / "auth.sqlite"),
        "RADIOSTAR_CACHE_PATH": str(tmp_path / "cache.sqlite"),
        "RADIOSTAR_CLAP_CHECKPOINT": str(tmp_path / "model.pt"),
    }

    settings = Settings.from_env(env)

    assert settings.host == "0.0.0.0"
    assert settings.port == 9000
    assert settings.auth_mode == "optional"
    assert settings.trust_proxy == "cloudflare"
    assert settings.max_threads == 4
    assert settings.daily_items == 10
    assert settings.db_path == tmp_path / "auth.sqlite"
    assert settings.cache_path == tmp_path / "cache.sqlite"
    assert settings.checkpoint == tmp_path / "model.pt"


def test_empty_paths_fall_back_to_defaults():
    env = {
        "RADIOSTAR_DB_PATH": "",
        "RADIOSTAR_CACHE_PATH": "",
        "RADIOSTAR_CLAP_CHECKPOINT": "",
    }

    settings = Settings.from_env(env)

    assert settings.db_path == DEFAULT_DB_PATH
    assert settings.cache_path == DEFAULT_CACHE_PATH
    assert settings.checkpoint is None


@pytest.mark.parametrize("value", ["nope", "65536", "-1"])
def test_invalid_port_is_rejected(value: str):
    with pytest.raises(ConfigError, match="RADIOSTAR_PORT"):
        Settings.from_env({"RADIOSTAR_PORT": value})


@pytest.mark.parametrize("value", ["sometimes", "ON"])
def test_invalid_auth_mode_is_rejected(value: str):
    with pytest.raises(ConfigError, match="RADIOSTAR_AUTH_MODE"):
        Settings.from_env({"RADIOSTAR_AUTH_MODE": value})


def test_invalid_thread_count_is_rejected():
    with pytest.raises(ConfigError, match="RADIOSTAR_MAX_THREADS"):
        Settings.from_env({"RADIOSTAR_MAX_THREADS": "0"})


def test_invalid_daily_quota_is_rejected():
    with pytest.raises(ConfigError, match="RADIOSTAR_EMBED_DAILY_ITEMS"):
        Settings.from_env({"RADIOSTAR_EMBED_DAILY_ITEMS": "-5"})


def test_settings_replace_validates(tmp_path: Path):
    settings = Settings.from_env({})

    updated = settings.replace(port=9999, db_path=tmp_path / "other.sqlite")

    assert updated.port == 9999
    assert updated.db_path == tmp_path / "other.sqlite"
    assert settings.port == 8787
    with pytest.raises(ConfigError, match="port"):
        settings.replace(port=70000)


def test_paths_expand_user_home(monkeypatch: pytest.MonkeyPatch, tmp_path: Path):
    monkeypatch.setenv("HOME", str(tmp_path))

    settings = Settings.from_env({"RADIOSTAR_DB_PATH": "~/auth.sqlite"})

    assert settings.db_path == tmp_path / "auth.sqlite"
