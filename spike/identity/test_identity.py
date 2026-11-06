"""Tests for canonical recording identity.

Run from the repository root:

    python3 -m pytest spike -q
"""

from __future__ import annotations

import sqlite3
from collections.abc import Iterator

import pytest

from spike.identity.identity import Resolver, artist_key, normalise, parse_title


@pytest.fixture
def db() -> Iterator[tuple[Resolver, sqlite3.Connection]]:
    conn = sqlite3.connect(":memory:")
    yield Resolver(conn), conn
    conn.close()


def count(conn: sqlite3.Connection, table: str) -> int:
    return int(conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])


# --------------------------------------------------------------------------
# String normalisation
# --------------------------------------------------------------------------


def test_diacritics_fold() -> None:
    assert normalise("Björk") == "bjork"
    assert normalise("Sigur Rós") == "sigur ros"


def test_punctuation_and_case_fold() -> None:
    assert normalise("Don't Stop Me Now!") == "don t stop me now"


def test_leading_track_number_is_stripped() -> None:
    assert normalise("07 - Blinding Lights") == "blinding lights"
    assert normalise("3. Song") == "song"


def test_title_that_starts_with_a_number_is_kept() -> None:
    assert normalise("7 Rings") == "7 rings"
    assert normalise("1999") == "1999"


def test_ampersand_becomes_and() -> None:
    assert normalise("Simon & Garfunkel") == "simon and garfunkel"


# --------------------------------------------------------------------------
# Artist keys
# --------------------------------------------------------------------------


def test_feature_credits_are_dropped_from_the_artist() -> None:
    assert artist_key("Daft Punk & Pharrell Williams") == "daft punk"
    assert artist_key("The Weeknd feat. Daft Punk") == "weeknd"
    assert artist_key("A x B") == "a"
    assert artist_key("A vs. B") == "a"


def test_leading_the_is_dropped_for_the_key_only() -> None:
    assert artist_key("The Beatles") == "beatles"
    assert artist_key("The The") == "the"


# --------------------------------------------------------------------------
# Title parsing
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "raw",
    [
        "Blinding Lights",
        "Blinding Lights (Official Video)",
        "Blinding Lights (Official Audio)",
        "Blinding Lights (Explicit)",
        "Blinding Lights (Remastered 2020)",
        "BLINDING LIGHTS (Lyrics)",
        "Blinding Lights (feat. X)",
        "Blinding Lights - Official Video",
        "Blinding Lights - Topic",
    ],
)
def test_same_recording_titles_collapse(raw: str) -> None:
    title, variant = parse_title(raw, artist="The Weeknd")
    assert title == "blinding lights"
    assert variant == "studio"


@pytest.mark.parametrize(
    ("raw", "variant"),
    [
        ("Blinding Lights (Live)", "live"),
        ("Blinding Lights (Remix)", "remix"),
        ("Blinding Lights (Acoustic)", "acoustic"),
        ("Blinding Lights (Sped Up)", "spedup"),
        ("Blinding Lights (Slowed)", "slowed"),
        ("Blinding Lights (Instrumental)", "instrumental"),
        ("Blinding Lights (Cover)", "cover"),
        ("Blinding Lights - Live at Wembley", "live"),
    ],
)
def test_version_titles_keep_their_variant(raw: str, variant: str) -> None:
    title, parsed = parse_title(raw, artist="The Weeknd")
    assert parsed == variant
    assert title == "blinding lights"


def test_song_titled_live_is_not_a_live_version() -> None:
    title, variant = parse_title("Live and Let Die", artist="Paul McCartney")
    assert title == "live and let die"
    assert variant == "studio"


def test_artist_prefix_in_title_is_dropped() -> None:
    title, variant = parse_title("Dermot Kennedy - Giants", artist="Dermot Kennedy")
    assert title == "giants"
    assert variant == "studio"


def test_content_in_parentheses_is_kept() -> None:
    title, variant = parse_title("Song (Part 2)", artist="X")
    assert title == "song part 2"
    assert variant == "studio"


# --------------------------------------------------------------------------
# Resolver
# --------------------------------------------------------------------------

ALIASES = [
    ("v1", "Blinding Lights"),
    ("v2", "Blinding Lights (Official Video)"),
    ("v3", "Blinding Lights (Official Audio)"),
    ("v4", "Blinding Lights (Explicit)"),
    ("v5", "Blinding Lights (Remastered 2020)"),
    ("v6", "BLINDING LIGHTS (Lyrics)"),
    ("v7", "Blinding Lights (feat. X)"),
]


def test_duplicate_uploads_collapse_to_one_recording(db: tuple[Resolver, sqlite3.Connection]) -> None:
    resolver, conn = db
    ids = {
        resolver.resolve(video_id, "The Weeknd", title, 200_000).recording_id
        for video_id, title in ALIASES
    }
    assert len(ids) == 1
    assert count(conn, "recordings") == 1
    assert count(conn, "video_aliases") == len(ALIASES)


def test_versions_stay_separate(db: tuple[Resolver, sqlite3.Connection]) -> None:
    resolver, conn = db
    base = resolver.resolve("v1", "The Weeknd", "Blinding Lights", 200_000).recording_id
    for index, title in enumerate(
        [
            "Blinding Lights (Live)",
            "Blinding Lights (Remix)",
            "Blinding Lights (Acoustic)",
            "Blinding Lights (Sped Up)",
            "Blinding Lights (Instrumental)",
        ]
    ):
        other = resolver.resolve(f"x{index}", "The Weeknd", title, 200_000).recording_id
        assert other != base
    assert count(conn, "recordings") == 6


def test_duration_filter_keeps_a_radio_edit_apart(db: tuple[Resolver, sqlite3.Connection]) -> None:
    resolver, conn = db
    album = resolver.resolve("a", "The Weeknd", "Blinding Lights", 245_000).recording_id
    edit = resolver.resolve("b", "The Weeknd", "Blinding Lights", 200_000).recording_id
    assert album != edit
    assert count(conn, "recordings") == 2


def test_near_equal_durations_merge(db: tuple[Resolver, sqlite3.Connection]) -> None:
    resolver, conn = db
    first = resolver.resolve("a", "The Weeknd", "Blinding Lights", 245_000).recording_id
    second = resolver.resolve("b", "The Weeknd", "Blinding Lights", 247_500).recording_id
    assert first == second
    assert count(conn, "recordings") == 1


def test_resolution_is_idempotent_per_video_id(db: tuple[Resolver, sqlite3.Connection]) -> None:
    resolver, conn = db
    first = resolver.resolve("v1", "The Weeknd", "Blinding Lights", 200_000)
    second = resolver.resolve("v1", "The Weeknd", "Blinding Lights", 200_000)
    assert first.recording_id == second.recording_id
    assert second.created is False
    assert count(conn, "video_aliases") == 1


def test_missing_duration_matches_on_text(db: tuple[Resolver, sqlite3.Connection]) -> None:
    resolver, conn = db
    known = resolver.resolve("a", "The Weeknd", "Blinding Lights", 200_000).recording_id
    unknown = resolver.resolve("b", "The Weeknd", "Blinding Lights", None).recording_id
    assert known == unknown


def test_artist_prefix_in_title_resolves_to_the_same_recording(
    db: tuple[Resolver, sqlite3.Connection],
) -> None:
    resolver, _ = db
    clean = resolver.resolve("a", "Dermot Kennedy", "Giants", 180_000).recording_id
    messy = resolver.resolve("b", "Dermot Kennedy", "Dermot Kennedy - Giants", 180_000).recording_id
    assert clean == messy


def test_diacritics_do_not_split_the_identity(db: tuple[Resolver, sqlite3.Connection]) -> None:
    resolver, _ = db
    first = resolver.resolve("a", "Björk", "Jóga", 300_000).recording_id
    second = resolver.resolve("b", "Bjork", "Joga", 300_000).recording_id
    assert first == second


def test_created_flag_is_true_only_once(db: tuple[Resolver, sqlite3.Connection]) -> None:
    resolver, _ = db
    assert resolver.resolve("a", "X", "Song", 100_000).created is True
    assert resolver.resolve("b", "X", "Song", 101_000).created is False
