#!/usr/bin/env python3
"""LAN HTTP service that returns CLAP text embeddings.

Run from anywhere; the process chdirs to the repo root so the existing
SQLite cache path in `text_embedder.py` resolves. Standard library only.

    /tmp/opencode/clap-venv/bin/python server/app.py
"""

from __future__ import annotations

import json
import os
import socket
import sys
import threading
import traceback
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from text_embedder import TextEmbedder

REPO_ROOT = Path(__file__).resolve().parent.parent
HOST = "0.0.0.0"
PORT = 8787
MODEL_NAME = "clap-htsat-tiny"
MAX_BODY_BYTES = 1 << 20
MAX_ITEMS = 256


class BadRequest(ValueError):
    """Client sent a body that does not match the endpoint schema."""


def parse_items(payload: Any) -> list[tuple[str, str]]:
    if not isinstance(payload, dict):
        raise BadRequest("body must be a JSON object")
    items = payload.get("items")
    if not isinstance(items, list):
        raise BadRequest("'items' must be a list")
    if len(items) > MAX_ITEMS:
        raise BadRequest(f"too many items (max {MAX_ITEMS})")

    parsed: list[tuple[str, str]] = []
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            raise BadRequest(f"items[{index}] must be an object")
        artist = item.get("artist")
        title = item.get("title")
        if not isinstance(artist, str) or not artist.strip():
            raise BadRequest(f"items[{index}].artist must be a non-empty string")
        if not isinstance(title, str) or not title.strip():
            raise BadRequest(f"items[{index}].title must be a non-empty string")
        parsed.append((artist, title))
    return parsed


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


class EmbedHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "RadioStarEmbed/1.0"

    embedder: TextEmbedder
    lock: threading.Lock

    def _send_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.send_header("connection", "close")
        self.end_headers()
        self.close_connection = True
        self.wfile.write(body)

    def _send_error_json(self, status: HTTPStatus, message: str) -> None:
        self._send_json(status, {"error": message})

    def do_GET(self) -> None:
        if self.path.split("?", 1)[0] == "/health":
            self._send_json(HTTPStatus.OK, {"ok": True, "model": MODEL_NAME})
            return
        self._send_error_json(HTTPStatus.NOT_FOUND, "not found")

    def do_POST(self) -> None:
        if self.path.split("?", 1)[0] != "/embed":
            self._send_error_json(HTTPStatus.NOT_FOUND, "not found")
            return

        try:
            payload = self._read_json_body()
            items = parse_items(payload)
        except BadRequest as exc:
            self._send_error_json(HTTPStatus.BAD_REQUEST, str(exc))
            return

        try:
            with self.lock:
                vectors = [self.embedder.embed(artist, title).tolist() for artist, title in items]
        except Exception as exc:
            traceback.print_exc(file=sys.stderr)
            self._send_error_json(HTTPStatus.INTERNAL_SERVER_ERROR, f"embed failed: {exc}")
            return

        self._send_json(HTTPStatus.OK, {"vectors": vectors})

    def _read_json_body(self) -> Any:
        raw_length = self.headers.get("content-length")
        if raw_length is None:
            raise BadRequest("content-length header required")
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise BadRequest("invalid content-length header") from exc
        if length < 0 or length > MAX_BODY_BYTES:
            raise BadRequest(f"body too large (max {MAX_BODY_BYTES} bytes)")

        raw = self.rfile.read(length)
        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise BadRequest(f"invalid JSON: {exc.msg}") from exc


def main() -> int:
    os.chdir(REPO_ROOT)

    print("loading CLAP text tower (about 20 s) ...", file=sys.stderr, flush=True)
    embedder = TextEmbedder()
    EmbedHandler.embedder = embedder
    EmbedHandler.lock = threading.Lock()

    server = ThreadingHTTPServer((HOST, PORT), EmbedHandler)
    server.daemon_threads = True
    lan_ip = detect_lan_ip()

    print(f"ready: http://{lan_ip}:{PORT}", flush=True)
    print(
        f"listening on {HOST}:{PORT}; phone should use http://{lan_ip}:{PORT}",
        file=sys.stderr,
        flush=True,
    )

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("shutting down", file=sys.stderr, flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
