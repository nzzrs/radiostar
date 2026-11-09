"""Small network helpers shared by the app and the HTTP layer."""

from __future__ import annotations

import ipaddress
import socket


def detect_lan_ip() -> str:
    """Best-effort LAN address. UDP connect sends no packets."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return str(sock.getsockname()[0])
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"
    finally:
        sock.close()


def is_loopback(value: str) -> bool:
    try:
        return ipaddress.ip_address(value).is_loopback
    except ValueError:
        return False
