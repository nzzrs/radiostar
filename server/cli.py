#!/usr/bin/env python3
"""Setup and administration for the RadioStar server.

    python -m server setup          create the owner account
    python -m server serve          run the HTTP service
    python -m server doctor         check dependencies, paths and port
    python -m server pair           print a one-time pairing code

Every command also works through ``python server/admin.py <command>``, the
older entry point.
"""

from __future__ import annotations

import argparse
import getpass
import importlib.util
import platform
import socket
import sys
import time
from pathlib import Path

if __package__ in (None, ""):  # allow `python server/cli.py`
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from server.auth import (
    MIN_PASSWORD_LENGTH,
    PAIRING_TTL_SECONDS,
    hash_password,
    hash_token,
    new_pairing_code,
    normalize_pairing_code,
)
from server.auth_store import AuthStore
from server.config import AUTH_MODES, ConfigError, Settings

PASSWORD_HELP = "read the password from stdin instead of a terminal prompt"


class CliError(Exception):
    """A command cannot continue because of user input or the environment."""


def _read_password(*, confirm: bool, from_stdin: bool) -> str:
    if from_stdin:
        password = sys.stdin.readline().rstrip("\r\n")
        if not password:
            raise CliError("no password received on stdin")
        if len(password) < MIN_PASSWORD_LENGTH:
            raise CliError(
                f"password must be at least {MIN_PASSWORD_LENGTH} characters"
            )
        return password

    if not sys.stdin.isatty():
        raise CliError("no terminal for a password prompt; use --password-stdin")
    while True:
        first = getpass.getpass("Password: ")
        if len(first) < MIN_PASSWORD_LENGTH:
            print(
                f"Password must be at least {MIN_PASSWORD_LENGTH} characters.",
                file=sys.stderr,
            )
            continue
        if confirm:
            second = getpass.getpass("Repeat password: ")
            if first != second:
                print("Passwords do not match.", file=sys.stderr)
                continue
        return first


def _clean_username(username: str) -> str:
    cleaned = str(username or "").strip()
    if not cleaned:
        raise CliError("username must not be empty")
    return cleaned


def _get_user(store: AuthStore, username: str):
    user = store.get_user_by_username(_clean_username(username))
    if user is None:
        raise CliError(f"unknown user: {username}")
    return user


def _pairing_code(store: AuthStore, user_id: str) -> str:
    code = new_pairing_code()
    expires_at = int(time.time()) + PAIRING_TTL_SECONDS
    store.add_pairing_code(
        hash_token(normalize_pairing_code(code)),
        user_id,
        expires_at,
    )
    return code


def _settings(args: argparse.Namespace, *, include_server: bool = False) -> Settings:
    settings = Settings.from_env()
    changes: dict[str, object] = {}

    db = getattr(args, "db", None)
    if db:
        changes["db_path"] = Path(db).expanduser()

    if include_server:
        for attribute, field in (
            ("host", "host"),
            ("port", "port"),
            ("auth_mode", "auth_mode"),
            ("trust_proxy", "trust_proxy"),
            ("max_threads", "max_threads"),
            ("daily_items", "daily_items"),
            ("cache", "cache_path"),
            ("checkpoint", "checkpoint"),
        ):
            value = getattr(args, attribute, None)
            if value is not None:
                changes[field] = (
                    Path(value).expanduser()
                    if field in {"cache_path", "checkpoint"}
                    else value
                )

    return settings.replace(**changes)


# -- commands ----------------------------------------------------------


def cmd_setup(args: argparse.Namespace) -> int:
    username = _clean_username(args.user)
    settings = _settings(args)
    store = AuthStore(settings.db_path)
    try:
        user = store.get_user_by_username(username)
        if user is None:
            password = _read_password(
                confirm=True,
                from_stdin=args.password_stdin,
            )
            user_id = store.create_user(username, hash_password(password))
            print(f"Created account '{username}'.")
        else:
            user_id = user["id"]
            print(f"Account '{username}' already exists.")

        print(f"Database: {settings.db_path}")
        if not args.no_pair:
            code = _pairing_code(store, user_id)
            print(f"Pairing code: {code} (valid 5 minutes)")
            print(
                "Enter it in Settings -> RadioStar -> Server account -> Pairing code."
            )
        print()
        print("Next:")
        print("  python -m server serve")
        print("  python -m server doctor")
        return 0
    finally:
        store.close()


def cmd_serve(args: argparse.Namespace) -> int:
    from server.app import serve

    return serve(_settings(args, include_server=True))


def cmd_pair(args: argparse.Namespace) -> int:
    settings = _settings(args)
    store = AuthStore(settings.db_path)
    try:
        user = _get_user(store, args.user)
        code = _pairing_code(store, user["id"])
        print(f"Pairing code for '{user['username']}' (valid 5 minutes):")
        print(f"  {code}")
        print("Enter it in Settings -> RadioStar -> Server account -> Pairing code.")
        return 0
    finally:
        store.close()


def cmd_devices(args: argparse.Namespace) -> int:
    settings = _settings(args)
    store = AuthStore(settings.db_path)
    try:
        user = _get_user(store, args.user)
        rows = store.list_devices(user["id"])
        if not rows:
            print("No devices.")
            return 0
        print(f"{'id':34}  {'name':24}  last seen  revoked")
        for row in rows:
            print(
                f"{row['id']:34}  {row['name'][:24]:24}  "
                f"{row['last_seen']:10}  {'yes' if row['revoked_at'] else 'no'}"
            )
        return 0
    finally:
        store.close()


def cmd_revoke(args: argparse.Namespace) -> int:
    settings = _settings(args)
    store = AuthStore(settings.db_path)
    try:
        if not store.revoke_device(args.device_id):
            raise CliError("device not found or already revoked")
        print("Device revoked.")
        return 0
    finally:
        store.close()


def cmd_passwd(args: argparse.Namespace) -> int:
    settings = _settings(args)
    store = AuthStore(settings.db_path)
    try:
        user = _get_user(store, args.user)
        password = _read_password(confirm=True, from_stdin=args.password_stdin)
        store.set_password(user["id"], hash_password(password))
        print("Password updated. All existing sessions were revoked.")
        return 0
    finally:
        store.close()


def cmd_user_create(args: argparse.Namespace) -> int:
    username = _clean_username(args.user)
    settings = _settings(args)
    store = AuthStore(settings.db_path)
    try:
        if store.get_user_by_username(username) is not None:
            raise CliError(f"user already exists: {username}")
        password = _read_password(confirm=True, from_stdin=args.password_stdin)
        store.create_user(username, hash_password(password))
        print(f"Created account '{username}'.")
        return 0
    finally:
        store.close()


def cmd_doctor(args: argparse.Namespace) -> int:
    settings = _settings(args, include_server=True)
    failures = 0

    def report(ok: bool, message: str) -> None:
        nonlocal failures
        if ok:
            print(f"[ok]   {message}")
        else:
            failures += 1
            print(f"[fail] {message}")

    def warn(message: str) -> None:
        print(f"[warn] {message}")

    print("RadioStar server doctor")
    report(sys.version_info >= (3, 10), f"Python {platform.python_version()} (>= 3.10)")

    for module in ("argon2", "numpy", "laion_clap"):
        report(_module_available(module), f"python module {module}")

    report(
        _ensure_directory(settings.db_path.parent),
        f"database directory {settings.db_path.parent}",
    )
    if settings.db_path.exists():
        try:
            store = AuthStore(settings.db_path)
            try:
                users = store.user_count()
            finally:
                store.close()
            report(
                True, f"database readable ({users} account{'s' if users != 1 else ''})"
            )
            if users == 0:
                warn("database has no account yet; run: python -m server setup")
        except Exception as exc:  # noqa: BLE001 - report any database failure
            report(False, f"database readable: {exc}")
    else:
        warn("database not created yet; run: python -m server setup")

    report(
        _ensure_directory(settings.cache_path.parent),
        f"cache directory {settings.cache_path.parent}",
    )
    if settings.checkpoint is not None:
        report(settings.checkpoint.exists(), f"CLAP checkpoint {settings.checkpoint}")

    if settings.port == 0:
        print("[ok]   port check skipped (port 0)")
    else:
        report(
            _port_available(settings.host, settings.port),
            f"bind {settings.host}:{settings.port}",
        )

    if failures:
        print(f"\n{failures} check(s) failed.")
        return 1
    print("\nAll checks passed.")
    return 0


def _module_available(name: str) -> bool:
    try:
        return importlib.util.find_spec(name) is not None
    except (ImportError, ValueError):
        return False


def _ensure_directory(path: Path) -> bool:
    try:
        path.mkdir(parents=True, exist_ok=True)
        return True
    except OSError:
        return False


def _port_available(host: str, port: int) -> bool:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((host, port))
        return True
    except OSError:
        return False


# -- parser ------------------------------------------------------------


def _add_db(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--db",
        default=argparse.SUPPRESS,
        metavar="PATH",
        help="SQLite database path (default: server/data/auth.sqlite)",
    )


def _add_setup_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--user", default="owner", help="account name (default: owner)")
    parser.add_argument("--password-stdin", action="store_true", help=PASSWORD_HELP)
    parser.add_argument(
        "--no-pair",
        action="store_true",
        help="do not print a pairing code at the end",
    )
    _add_db(parser)
    parser.set_defaults(func=cmd_setup)


def _add_server_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--host", metavar="ADDR", help="bind address (default: 127.0.0.1)"
    )
    parser.add_argument(
        "--port", type=int, metavar="PORT", help="bind port (default: 8787)"
    )
    parser.add_argument(
        "--auth-mode", choices=sorted(AUTH_MODES), help="authentication mode"
    )
    parser.add_argument(
        "--trust-proxy", metavar="MODE", help="set 'cloudflare' behind a tunnel"
    )
    parser.add_argument(
        "--max-threads", type=int, metavar="N", help="handler thread cap"
    )
    parser.add_argument(
        "--daily-items", type=int, metavar="N", help="daily item quota per device"
    )
    parser.add_argument("--cache", metavar="PATH", help="text embedding cache database")
    parser.add_argument("--checkpoint", metavar="PATH", help="CLAP checkpoint file")
    _add_db(parser)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m server",
        description="RadioStar embedding and sync server.",
    )
    _add_db(parser)
    sub = parser.add_subparsers(dest="command", metavar="command")

    setup = sub.add_parser(
        "setup",
        help="create the owner account and print a pairing code",
    )
    _add_setup_arguments(setup)

    init = sub.add_parser("init", help="alias for setup")
    _add_setup_arguments(init)

    serve = sub.add_parser("serve", help="start the HTTP service (default)")
    _add_server_arguments(serve)
    serve.set_defaults(func=cmd_serve)

    pair = sub.add_parser("pair", help="print a one-time pairing code")
    pair.add_argument("--user", required=True)
    pair.add_argument("--device", help="device name (informational)")
    _add_db(pair)
    pair.set_defaults(func=cmd_pair)

    devices = sub.add_parser("devices", help="list linked devices")
    devices.add_argument("--user", required=True)
    _add_db(devices)
    devices.set_defaults(func=cmd_devices)

    for name in ("revoke", "device-revoke"):
        revoke = sub.add_parser(name, help="revoke one device")
        revoke.add_argument("device_id")
        _add_db(revoke)
        revoke.set_defaults(func=cmd_revoke)

    passwd = sub.add_parser("passwd", help="change a password and revoke sessions")
    passwd.add_argument("--user", required=True)
    passwd.add_argument("--password-stdin", action="store_true", help=PASSWORD_HELP)
    _add_db(passwd)
    passwd.set_defaults(func=cmd_passwd)

    create = sub.add_parser("user-create", help="create another account")
    create.add_argument("--user", required=True)
    create.add_argument("--password-stdin", action="store_true", help=PASSWORD_HELP)
    _add_db(create)
    create.set_defaults(func=cmd_user_create)

    doctor = sub.add_parser("doctor", help="check dependencies, paths and port")
    _add_server_arguments(doctor)
    doctor.set_defaults(func=cmd_doctor)

    return parser


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        argv = ["serve"]
    parser = build_parser()
    args = parser.parse_args(argv)
    if not hasattr(args, "func"):
        parser.error("a command is required (try: python -m server setup)")
    try:
        return int(args.func(args))
    except ConfigError as exc:
        print(f"configuration error: {exc}", file=sys.stderr)
        return 2
    except CliError as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
