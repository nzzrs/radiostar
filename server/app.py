#!/usr/bin/env python3
"""Authenticated HTTP service that returns CLAP text embeddings.

Run with the package entry point:

    python -m server setup
    python -m server serve

The service binds loopback by default. Put Cloudflare Tunnel (or another
reverse proxy) in front of it; never expose port 8787 directly.
"""

from __future__ import annotations

import sys
from pathlib import Path

if __package__ in (None, ""):  # allow `python server/app.py`
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from server.auth import (  # noqa: F401 - public compatibility re-exports
    AuthError,
    DailyQuota,
    Unauthorized,
)
from server.config import DEFAULT_DB_PATH, ConfigError, Settings
from server.context import AppContext, build_context
from server.http.handler import EmbedHandler
from server.http.server import BoundedThreadingHTTPServer
from server.net import detect_lan_ip
from server.text_embedder import TextEmbedder

DEFAULT_DB = DEFAULT_DB_PATH


def create_server(
    context: AppContext,
    host: str | None = None,
    port: int | None = None,
) -> BoundedThreadingHTTPServer:
    """Create the HTTP server for an application context."""
    settings = context.settings or Settings()
    bind_host = settings.host if host is None else host
    bind_port = settings.port if port is None else port
    EmbedHandler.ctx = context
    return BoundedThreadingHTTPServer(
        (bind_host, bind_port),
        EmbedHandler,
        max_threads=settings.max_threads,
    )


def serve(settings: Settings | None = None) -> int:
    """Load the text tower and serve until interrupted."""
    settings = Settings.from_env() if settings is None else settings
    context = build_context(None, settings=settings)

    if settings.auth_mode == "required" and context.store.user_count() == 0:
        print(
            "No account exists yet. Run: python -m server setup",
            file=sys.stderr,
            flush=True,
        )

    print("loading CLAP text tower (about 20 s) ...", file=sys.stderr, flush=True)
    try:
        context.embedder = TextEmbedder(
            checkpoint=settings.checkpoint,
            cache_path=settings.cache_path,
        )
        server = create_server(context, settings.host, settings.port)
    except BaseException:
        _close_embedder(context.embedder)
        context.store.close()
        raise

    display_host = (
        detect_lan_ip() if settings.host in {"", "0.0.0.0", "::"} else settings.host
    )
    print(f"ready: http://{display_host}:{settings.port}", flush=True)
    print(
        f"listening on {settings.host}:{settings.port}; "
        f"auth mode={settings.auth_mode}; "
        "put Cloudflare Tunnel in front, do not expose this port",
        file=sys.stderr,
        flush=True,
    )

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("shutting down", file=sys.stderr, flush=True)
    finally:
        server.server_close()
        _close_embedder(context.embedder)
        context.store.close()
    return 0


def _close_embedder(embedder: object) -> None:
    close = getattr(embedder, "close", None)
    if callable(close):
        close()


def main() -> int:
    try:
        settings = Settings.from_env()
    except ConfigError as exc:
        print(f"configuration error: {exc}", file=sys.stderr)
        return 2
    return serve(settings)


if __name__ == "__main__":
    raise SystemExit(main())
