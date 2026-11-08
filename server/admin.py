#!/usr/bin/env python3
"""Local administration for the RadioStar embedding server.

Run this on the machine that owns the server. It never talks to the network:

    python -m server.admin init
    python -m server.admin pair --user owner --device "Pixel"
    python -m server.admin devices --user owner
    python -m server.admin device-revoke <device-id>
    python -m server.admin passwd --user owner
"""

from __future__ import annotations

import argparse
import getpass
import os
import sys
import time
from pathlib import Path

try:  # package import (`python -m server.admin`) and script import both work
    from .auth import (
        MIN_PASSWORD_LENGTH,
        PAIRING_TTL_SECONDS,
        hash_password,
        hash_token,
        new_pairing_code,
        normalize_pairing_code,
    )
    from .auth_store import AuthStore
except ImportError:  # pragma: no cover
    from auth import (  # type: ignore
        MIN_PASSWORD_LENGTH,
        PAIRING_TTL_SECONDS,
        hash_password,
        hash_token,
        new_pairing_code,
        normalize_pairing_code,
    )
    from auth_store import AuthStore  # type: ignore

DEFAULT_DB = Path(__file__).resolve().parent / "data" / "auth.sqlite"


def _db_path(args: argparse.Namespace) -> Path:
    return Path(args.db or os.environ.get("RADIOSTAR_DB_PATH") or DEFAULT_DB)


def _ask_password(confirm: bool = True) -> str:
    while True:
        first = getpass.getpass("Password: ")
        if len(first) < MIN_PASSWORD_LENGTH:
            print(f"Password must be at least {MIN_PASSWORD_LENGTH} characters.", file=sys.stderr)
            continue
        if confirm:
            second = getpass.getpass("Repeat password: ")
            if first != second:
                print("Passwords do not match.", file=sys.stderr)
                continue
        return first


def _get_user(store: AuthStore, username: str):
    user = store.get_user_by_username(username)
    if user is None:
        print(f"Unknown user: {username}", file=sys.stderr)
        raise SystemExit(1)
    return user


def cmd_init(args: argparse.Namespace) -> int:
    store = AuthStore(_db_path(args))
    try:
        if store.user_count() > 0:
            print("An account already exists. Use 'user-create' for another one.", file=sys.stderr)
            return 1
        username = (args.user or input("Username [owner]: ").strip() or "owner")
        password = _ask_password()
        store.create_user(username, hash_password(password))
        print(f"Created account '{username}'.")
        print("Pair a device with: python -m server.admin pair --user " + username)
        return 0
    finally:
        store.close()


def cmd_user_create(args: argparse.Namespace) -> int:
    store = AuthStore(_db_path(args))
    try:
        username = args.user
        if store.get_user_by_username(username) is not None:
            print(f"User already exists: {username}", file=sys.stderr)
            return 1
        password = _ask_password()
        store.create_user(username, hash_password(password))
        print(f"Created account '{username}'.")
        return 0
    finally:
        store.close()


def cmd_passwd(args: argparse.Namespace) -> int:
    store = AuthStore(_db_path(args))
    try:
        user = _get_user(store, args.user)
        password = _ask_password()
        store.set_password(user["id"], hash_password(password))
        print("Password updated. All existing sessions were revoked.")
        return 0
    finally:
        store.close()


def cmd_pair(args: argparse.Namespace) -> int:
    store = AuthStore(_db_path(args))
    try:
        user = _get_user(store, args.user)
        code = new_pairing_code()
        expires_at = int(time.time()) + PAIRING_TTL_SECONDS
        store.add_pairing_code(hash_token(normalize_pairing_code(code)), user["id"], expires_at)
        print(f"Pairing code for '{user['username']}' (valid 5 minutes):")
        print(f"  {code}")
        print("Enter it in Settings -> RadioStar -> Server -> Link device.")
        return 0
    finally:
        store.close()


def cmd_devices(args: argparse.Namespace) -> int:
    store = AuthStore(_db_path(args))
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


def cmd_device_revoke(args: argparse.Namespace) -> int:
    store = AuthStore(_db_path(args))
    try:
        if not store.revoke_device(args.device_id):
            print("Device not found or already revoked.", file=sys.stderr)
            return 1
        print("Device revoked.")
        return 0
    finally:
        store.close()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", help="SQLite auth database path")
    sub = parser.add_subparsers(dest="command", required=True)

    init = sub.add_parser("init", help="create the first account")
    init.add_argument("--user", help="username (default: owner)")
    init.set_defaults(func=cmd_init)

    create = sub.add_parser("user-create", help="create another account")
    create.add_argument("--user", required=True)
    create.set_defaults(func=cmd_user_create)

    passwd = sub.add_parser("passwd", help="change a password and revoke sessions")
    passwd.add_argument("--user", required=True)
    passwd.set_defaults(func=cmd_passwd)

    pair = sub.add_parser("pair", help="generate a one-time pairing code")
    pair.add_argument("--user", required=True)
    pair.add_argument("--device", help="device name (informational)")
    pair.set_defaults(func=cmd_pair)

    devices = sub.add_parser("devices", help="list devices")
    devices.add_argument("--user", required=True)
    devices.set_defaults(func=cmd_devices)

    revoke = sub.add_parser("device-revoke", help="revoke a device")
    revoke.add_argument("device_id")
    revoke.set_defaults(func=cmd_device_revoke)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
