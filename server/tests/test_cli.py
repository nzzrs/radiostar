from __future__ import annotations

import io
import re
from pathlib import Path

import pytest

from server.auth import TokenService
from server.auth_store import AuthStore
from server.cli import main

PASSWORD = "correct horse battery staple"


def _setup(
    db_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    *extra: str,
) -> int:
    monkeypatch.setattr("sys.stdin", io.StringIO(PASSWORD + "\n"))
    return main(
        [
            "setup",
            "--db",
            str(db_path),
            "--user",
            "owner",
            "--password-stdin",
            *extra,
        ]
    )


def test_setup_creates_account_once(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys
):
    db_path = tmp_path / "auth.sqlite"

    assert _setup(db_path, monkeypatch, "--no-pair") == 0
    output = capsys.readouterr().out
    assert "owner" in output
    assert "python -m server serve" in output

    store = AuthStore(db_path)
    try:
        assert store.user_count() == 1
    finally:
        store.close()

    assert _setup(db_path, monkeypatch, "--no-pair") == 0
    assert "already exists" in capsys.readouterr().out

    store = AuthStore(db_path)
    try:
        assert store.user_count() == 1
    finally:
        store.close()


def test_setup_prints_a_working_pairing_code(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys,
):
    db_path = tmp_path / "auth.sqlite"

    assert _setup(db_path, monkeypatch) == 0
    match = re.search(r"\b([A-Z2-9]{5}-[A-Z2-9]{5})\b", capsys.readouterr().out)
    assert match is not None, "setup must print a pairing code"

    store = AuthStore(db_path)
    try:
        issued = TokenService(store).pair(match.group(1), "Test phone")
        assert issued.username == "owner"
    finally:
        store.close()


def test_init_is_an_alias_for_setup(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys
):
    db_path = tmp_path / "auth.sqlite"
    monkeypatch.setattr("sys.stdin", io.StringIO(PASSWORD + "\n"))

    code = main(
        [
            "init",
            "--db",
            str(db_path),
            "--user",
            "owner",
            "--password-stdin",
            "--no-pair",
        ]
    )

    assert code == 0
    assert "owner" in capsys.readouterr().out


def test_doctor_reports_a_healthy_setup(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys,
):
    monkeypatch.setattr("server.cli._module_available", lambda name: True)

    code = main(
        [
            "doctor",
            "--db",
            str(tmp_path / "auth.sqlite"),
            "--cache",
            str(tmp_path / "text_cache.sqlite"),
            "--host",
            "127.0.0.1",
            "--port",
            "0",
        ]
    )

    output = capsys.readouterr().out
    assert code == 0
    assert "[ok]" in output


def test_doctor_fails_when_a_dependency_is_missing(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys,
):
    monkeypatch.setattr(
        "server.cli._module_available", lambda name: name != "laion_clap"
    )

    code = main(["doctor", "--db", str(tmp_path / "auth.sqlite")])

    output = capsys.readouterr().out
    assert code == 1
    assert "[fail]" in output
