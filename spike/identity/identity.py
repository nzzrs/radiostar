"""Canonical recording identity for a YouTube Music client.

``videoId`` identifies a *video*, not a recording. The same recording appears
as the album upload, a Topic auto-track, a lyric video, an explicit edit and a
remaster. If history and embeddings are keyed by ``videoId``, skips fragment
across duplicates and one song can fill four slots in a 30-track slate.

This module collapses those aliases onto one canonical recording:

    video_aliases(video_id)  ->  recording_id
    recordings(recording_id) ->  artist_key, title_key, variant, duration_ms

Two matching signals are used:

* ``(artist_key, title_key, variant)`` — exact, after normalisation.
* duration within +/- 3 s — a filter, never part of the hash, because two
  uploads of one recording routinely differ by 1-3 s of silence or encoder
  padding, while a radio edit differs by 20-40 s and must stay separate.

Version tags are split into two vocabularies. Tags that describe the *same*
recording (remaster, official video, explicit) are removed. Tags that describe
a *different* recording (live, remix, acoustic, sped up) become part of the
canonical key, so those versions never merge.
"""

from __future__ import annotations

import re
import sqlite3
import unicodedata
import uuid
from dataclasses import dataclass

DURATION_TOLERANCE_MS = 3_000

SCHEMA = """
CREATE TABLE IF NOT EXISTS recordings (
    recording_id TEXT PRIMARY KEY,
    artist_key   TEXT NOT NULL,
    title_key    TEXT NOT NULL,
    variant      TEXT NOT NULL DEFAULT 'studio',
    duration_ms  INTEGER NOT NULL,
    mbid         TEXT,
    isrc         TEXT,
    created_at   INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_recordings_lookup
    ON recordings (artist_key, title_key, variant);

CREATE TABLE IF NOT EXISTS video_aliases (
    video_id     TEXT PRIMARY KEY,
    recording_id TEXT NOT NULL REFERENCES recordings (recording_id),
    first_seen   INTEGER NOT NULL DEFAULT 0,
    source       TEXT NOT NULL DEFAULT 'ytmusic'
);
"""

# Ordered: the first match wins. "live remix" is a live version.
_VARIANT_PRIORITY: tuple[tuple[str, str], ...] = (
    ("sped up", "spedup"),
    ("speed up", "spedup"),
    ("nightcore", "spedup"),
    ("slowed", "slowed"),
    ("live", "live"),
    ("unplugged", "acoustic"),
    ("acoustic", "acoustic"),
    ("remix", "remix"),
    ("rmx", "remix"),
    ("bootleg", "remix"),
    ("instrumental", "instrumental"),
    ("karaoke", "instrumental"),
    ("cover", "cover"),
    ("tribute", "cover"),
    ("demo", "demo"),
)

# Tags that describe the same recording and are removed from the key.
_NOISE_PHRASES: tuple[str, ...] = (
    "official video",
    "official audio",
    "official music video",
    "official",
    "music video",
    "lyric video",
    "lyrics",
    "with lyrics",
    "visualizer",
    "audio",
    "hd",
    "hq",
    "4k",
    "8k",
    "mv",
    "m/v",
    "remaster",
    "remastered",
    "digital remaster",
    "explicit",
    "clean",
    "topic",
)
_NOISE_TOKENS: frozenset[str] = frozenset(
    token for phrase in _NOISE_PHRASES for token in phrase.split()
) | {"m", "v"}

_ARTIST_SPLIT = re.compile(
    r"(?i)\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bvs\.?\b|\bwith\b|\s[x\u00d7]\s)\s*"
)
_FEAT_BRACKETED = re.compile(r"(?i)[\(\[]\s*(?:feat\.?|ft\.?|featuring)\s+[^\)\]]*[\)\]]")
_FEAT_INLINE = re.compile(r"(?i)\s+\b(?:feat\.?|ft\.?|featuring)\b.*$")
_BRACKETED = re.compile(r"[\(\[]([^\)\]]*)[\)\]]")
_SEGMENT_SPLIT = re.compile(r"\s+[-\u2013\u2014|•]\s+")
_TRACK_NUMBER = re.compile(r"^\s*\d{1,3}\s*[-\u2013\u2014.)]\s+")


def normalise(text: str) -> str:
    """Lowercase, fold diacritics, strip punctuation, collapse whitespace."""
    s = _TRACK_NUMBER.sub("", text)
    s = unicodedata.normalize("NFKC", s).lower()
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch))
    s = s.replace("&", " and ").replace("+", " and ")
    s = "".join(ch if (ch.isalnum() or ch.isspace()) else " " for ch in s)
    return re.sub(r"\s+", " ", s).strip()


def artist_key(artist: str) -> str:
    """Primary artist only. ``"A & B"`` and ``"A feat. B"`` both give ``"a"``."""
    primary = _ARTIST_SPLIT.split(artist, maxsplit=1)[0].strip() or artist
    key = normalise(primary)
    if key.startswith("the "):
        key = key[4:]
    return key


def _classify(text: str, allow_variant: bool) -> tuple[str, str | None]:
    """Return ``("content" | "noise" | "variant", variant_tag)``.

    ``allow_variant`` is false for the first dash-separated segment, so a song
    actually titled "Live and Let Die" is not mistaken for a live version.
    """
    t = normalise(text)
    if not t:
        return "noise", None

    if allow_variant:
        for needle, variant in _VARIANT_PRIORITY:
            if re.search(rf"(?<!\w){re.escape(needle)}(?!\w)", t):
                return "variant", variant

    tokens = t.split()
    non_numeric = [token for token in tokens if not token.isdigit()]
    if non_numeric and all(token in _NOISE_TOKENS for token in non_numeric):
        return "noise", None
    return "content", None


def parse_title(title: str, artist: str | None = None) -> tuple[str, str]:
    """Return ``(title_key, variant)``."""
    variant = "studio"
    s = _FEAT_BRACKETED.sub(" ", title)
    s = _FEAT_INLINE.sub("", s)

    def drop_qualifier(match: re.Match[str]) -> str:
        nonlocal variant
        kind, var = _classify(match.group(1), allow_variant=True)
        if kind == "variant" and variant == "studio":
            variant = var
        return " " if kind in ("variant", "noise") else match.group(0)

    s = _BRACKETED.sub(drop_qualifier, s)

    segments = [seg for seg in _SEGMENT_SPLIT.split(s) if seg.strip()]
    kept: list[str] = []
    for index, segment in enumerate(segments):
        kind, var = _classify(segment, allow_variant=index > 0)
        if kind == "variant":
            if variant == "studio":
                variant = var
            continue
        if kind == "noise":
            continue
        kept.append(segment)

    # "Artist - Title" uploads: drop a leading segment that repeats the artist.
    if len(kept) >= 2 and artist:
        if normalise(kept[0]) == artist_key(artist):
            kept = kept[1:]

    key = normalise(" ".join(kept))
    if not key:
        key = normalise(title)
    return key, variant


@dataclass(frozen=True)
class Resolution:
    recording_id: str
    created: bool


class Resolver:
    """Resolve ``videoId`` to a canonical ``recording_id``."""

    def __init__(self, conn: sqlite3.Connection) -> None:
        self._conn = conn
        self._conn.executescript(SCHEMA)

    def resolve(
        self,
        video_id: str,
        artist: str,
        title: str,
        duration_ms: int | None = None,
        source: str = "ytmusic",
    ) -> Resolution:
        row = self._conn.execute(
            "SELECT recording_id FROM video_aliases WHERE video_id = ?",
            (video_id,),
        ).fetchone()
        if row is not None:
            return Resolution(recording_id=row[0], created=False)

        a_key = artist_key(artist)
        t_key, variant = parse_title(title, artist)

        candidates = self._conn.execute(
            "SELECT recording_id, duration_ms FROM recordings"
            " WHERE artist_key = ? AND title_key = ? AND variant = ?",
            (a_key, t_key, variant),
        ).fetchall()

        recording_id = self._match(candidates, duration_ms)
        created = recording_id is None

        if recording_id is None:
            recording_id = uuid.uuid4().hex
            self._conn.execute(
                "INSERT INTO recordings"
                " (recording_id, artist_key, title_key, variant, duration_ms)"
                " VALUES (?, ?, ?, ?, ?)",
                (recording_id, a_key, t_key, variant, int(duration_ms or 0)),
            )

        self._conn.execute(
            "INSERT OR IGNORE INTO video_aliases (video_id, recording_id, source)"
            " VALUES (?, ?, ?)",
            (video_id, recording_id, source),
        )
        self._conn.commit()
        return Resolution(recording_id=recording_id, created=created)

    @staticmethod
    def _match(
        candidates: list[tuple[str, int]], duration_ms: int | None
    ) -> str | None:
        if not candidates:
            return None
        if not duration_ms:
            return str(candidates[0][0])

        best: tuple[int, str] | None = None
        for recording_id, stored in candidates:
            if not stored:
                continue
            delta = abs(int(stored) - int(duration_ms))
            if delta > DURATION_TOLERANCE_MS:
                continue
            if best is None or delta < best[0]:
                best = (delta, str(recording_id))
        return best[1] if best is not None else None
