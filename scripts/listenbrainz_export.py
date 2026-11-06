#!/usr/bin/env python3
"""Download ListenBrainz listens and write RadioStar's CSV format."""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterable, Iterator
from typing import Any

API_ROOT = "https://api.listenbrainz.org/1/user"
PAGE_SIZE = 1000
DEFAULT_COUNT = 5000
REQUEST_TIMEOUT_SECONDS = 30
USER_AGENT = "radiostar-listenbrainz-export/1.0"
HEADER = ["Title", "Artist", "Album", "YouTube Video ID"]


class ListenBrainzError(Exception):
    """An API or network failure that should stop the export."""


def clean_text(value: object) -> str:
    """Return a CSV-safe cell without surrounding whitespace or newlines."""
    if value is None:
        return ""
    text = str(value).replace("\r", " ").replace("\n", " ").strip()
    return text


def listen_to_row(listen: dict[str, Any]) -> list[str]:
    """Transform one ListenBrainz listen into a CSV row."""
    metadata = listen.get("track_metadata")
    if not isinstance(metadata, dict):
        metadata = {}

    additional_info = metadata.get("additional_info")
    if not isinstance(additional_info, dict):
        additional_info = {}

    video_id = clean_text(additional_info.get("youtube_id"))
    if not video_id:
        video_id = clean_text(additional_info.get("video_id"))

    return [
        clean_text(metadata.get("track_name")),
        clean_text(metadata.get("artist_name")),
        clean_text(metadata.get("release_name")),
        video_id,
    ]


def dedupe_listens(
    listens: Iterable[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Keep the first listen for each non-empty recording_msid."""
    seen: set[str] = set()
    unique: list[dict[str, Any]] = []

    for listen in listens:
        recording_msid = listen.get("recording_msid")
        if recording_msid is not None and recording_msid != "":
            key = str(recording_msid)
            if key in seen:
                continue
            seen.add(key)
        unique.append(listen)

    return unique


def listens_to_rows(listens: Iterable[dict[str, Any]]) -> list[list[str]]:
    """Transform listens into non-empty CSV rows, preserving API order."""
    rows: list[list[str]] = []
    for listen in dedupe_listens(listens):
        row = listen_to_row(listen)
        if row[0]:
            rows.append(row)
    return rows


def extract_listens(payload: object) -> list[dict[str, Any]]:
    """Extract the listens list from a ListenBrainz API response."""
    if not isinstance(payload, dict):
        raise ValueError("response is not a JSON object")

    nested = payload.get("payload")
    if isinstance(nested, dict):
        payload = nested

    page = payload.get("listens", [])
    if not isinstance(page, list):
        raise ValueError("response has no listens list")

    return [listen for listen in page if isinstance(listen, dict)]


def _fetch_page(
    user: str,
    max_ts: int | None,
    token: str | None,
) -> list[dict[str, Any]]:
    quoted_user = urllib.parse.quote(user, safe="")
    url = f"{API_ROOT}/{quoted_user}/listens?count={PAGE_SIZE}"
    if max_ts is not None:
        url += f"&max_ts={max_ts}"

    headers = {
        "Accept": "application/json",
        "User-Agent": USER_AGENT,
    }
    if token:
        headers["Authorization"] = f"Token {token}"

    request = urllib.request.Request(url, headers=headers)

    try:
        with urllib.request.urlopen(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as exc:
        raise ListenBrainzError(f"HTTP {exc.code} for {url}") from None
    except urllib.error.URLError as exc:
        raise ListenBrainzError(
            f"network error for {url}: {exc.reason}"
        ) from None
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise ListenBrainzError(f"invalid JSON from {url}") from None

    try:
        return extract_listens(payload)
    except ValueError:
        raise ListenBrainzError(f"invalid response from {url}") from None


def iter_listens(
    user: str,
    token: str | None = None,
) -> Iterator[dict[str, Any]]:
    """Yield raw listens newest first, following API pagination."""
    max_ts: int | None = None

    while True:
        page = _fetch_page(user, max_ts, token)
        if not page:
            return

        for listen in page:
            yield listen

        timestamps = [
            timestamp
            for listen in page
            if isinstance((timestamp := listen.get("listened_at")), int)
        ]
        if not timestamps:
            return

        oldest = min(timestamps)
        if max_ts is not None and oldest >= max_ts:
            return

        max_ts = oldest
        time.sleep(0.1)


def iter_unique_listens(
    listens: Iterable[dict[str, Any]],
) -> Iterator[dict[str, Any]]:
    """Yield listens once each, deduplicated by recording_msid when present."""
    seen: set[str] = set()

    for listen in listens:
        recording_msid = listen.get("recording_msid")
        if recording_msid is not None and recording_msid != "":
            key = str(recording_msid)
            if key in seen:
                continue
            seen.add(key)
        yield listen


def collect_rows(
    user: str,
    count: int,
    token: str | None = None,
) -> list[list[str]]:
    """Fetch and transform up to count non-empty rows."""
    rows: list[list[str]] = []

    for listen in iter_unique_listens(iter_listens(user, token)):
        row = listen_to_row(listen)
        if not row[0]:
            continue
        rows.append(row)
        if len(rows) >= count:
            break

    return rows


def write_csv(path: str, rows: Iterable[list[str]]) -> int:
    """Write rows with the RadioStar header. Return the number written."""
    parent = os.path.dirname(os.path.abspath(path))
    os.makedirs(parent, exist_ok=True)

    written = 0
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(HEADER)
        for row in rows:
            writer.writerow(row)
            written += 1

    return written


def positive_int(value: str) -> int:
    """Argparse type for an integer greater than zero."""
    try:
        number = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError("must be an integer") from None
    if number < 1:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return number


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Export ListenBrainz listens as a RadioStar CSV.",
    )
    parser.add_argument(
        "--user",
        default=os.environ.get("LB_USER"),
        help="ListenBrainz username (default: LB_USER)",
    )
    parser.add_argument(
        "--out",
        default="listens.csv",
        help="output CSV path (default: listens.csv)",
    )
    parser.add_argument(
        "--count",
        type=positive_int,
        default=DEFAULT_COUNT,
        help=f"maximum rows to write (default: {DEFAULT_COUNT})",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    user = (args.user or "").strip()
    if not user:
        parser.error("--user is required unless the LB_USER environment variable is set")

    token = os.environ.get("LB_TOKEN")

    try:
        rows = collect_rows(user, args.count, token)
        written = write_csv(args.out, rows)
    except ListenBrainzError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"error: cannot write {args.out}: {exc}", file=sys.stderr)
        return 1

    print(f"Wrote {written} listens to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
