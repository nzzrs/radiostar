"""Tests for the ListenBrainz CSV exporter."""

from __future__ import annotations

import unittest
from typing import Any

try:
    from scripts.listenbrainz_export import (
        HEADER,
        dedupe_listens,
        extract_listens,
        listen_to_row,
        listens_to_rows,
    )
except ModuleNotFoundError:
    from listenbrainz_export import (  # type: ignore[no-redef]
        HEADER,
        dedupe_listens,
        extract_listens,
        listen_to_row,
        listens_to_rows,
    )


def make_listen(
    title: str = "Song",
    artist: str = "Artist",
    album: str | None = "Album",
    video_id: str | None = None,
    msid: str | None = "msid-1",
    listened_at: int = 1,
    extra: dict[str, Any] | None = None,
    **metadata_extra: Any,
) -> dict[str, Any]:
    metadata: dict[str, Any] = {
        "track_name": title,
        "artist_name": artist,
    }
    if album is not None:
        metadata["release_name"] = album

    additional_info: dict[str, Any] = dict(extra or {})
    if video_id is not None:
        additional_info["youtube_id"] = video_id
    if additional_info:
        metadata["additional_info"] = additional_info
    metadata.update(metadata_extra)

    listen: dict[str, Any] = {
        "listened_at": listened_at,
        "track_metadata": metadata,
    }
    if msid is not None:
        listen["recording_msid"] = msid
    return listen


class ListenToRowTests(unittest.TestCase):
    def test_header_and_column_order(self) -> None:
        self.assertEqual(
            HEADER,
            ["Title", "Artist", "Album", "YouTube Video ID"],
        )
        row = listen_to_row(
            make_listen(
                title="Song",
                artist="Artist",
                album="Album",
                video_id="abc123",
            )
        )
        self.assertEqual(row, ["Song", "Artist", "Album", "abc123"])

    def test_blank_title_is_skipped(self) -> None:
        rows = listens_to_rows(
            [
                make_listen(title="   ", msid="blank"),
                make_listen(title="Kept", msid="kept"),
            ]
        )
        self.assertEqual(rows, [["Kept", "Artist", "Album", ""]])

    def test_missing_album_and_video_id_become_empty_strings(self) -> None:
        row = listen_to_row(
            make_listen(
                title="Song",
                artist="Artist",
                album=None,
                video_id=None,
            )
        )
        self.assertEqual(row, ["Song", "Artist", "", ""])

    def test_extra_additional_info_is_ignored_and_video_id_falls_back(self) -> None:
        row = listen_to_row(
            make_listen(
                extra={"video_id": "fallback", "music_service": "spotify"},
                video_id=None,
            )
        )
        self.assertEqual(row[3], "fallback")

        row = listen_to_row(
            make_listen(
                extra={
                    "youtube_id": "preferred",
                    "video_id": "fallback",
                    "duration_ms": 123,
                }
            )
        )
        self.assertEqual(row[3], "preferred")

    def test_whitespace_and_newlines_are_removed(self) -> None:
        row = listen_to_row(
            make_listen(
                title="  Song\nTitle  ",
                artist=" Artist\r\nName ",
                album=" Album ",
            )
        )
        self.assertEqual(row, ["Song Title", "Artist  Name", "Album", ""])


class PayloadTests(unittest.TestCase):
    def test_extracts_listens_from_payload_wrapper(self) -> None:
        listen = make_listen()
        self.assertEqual(
            extract_listens({"payload": {"listens": [listen]}}),
            [listen],
        )

    def test_extracts_listens_from_top_level_response(self) -> None:
        listen = make_listen()
        self.assertEqual(extract_listens({"listens": [listen]}), [listen])


class DedupeTests(unittest.TestCase):
    def test_dedupe_by_recording_msid_keeps_first_occurrence(self) -> None:
        listens = [
            make_listen(title="First", msid="same"),
            make_listen(title="Second", msid="other"),
            make_listen(title="Duplicate", msid="same"),
        ]

        self.assertEqual(
            [listen["track_metadata"]["track_name"] for listen in dedupe_listens(listens)],
            ["First", "Second"],
        )
        self.assertEqual(
            listens_to_rows(listens),
            [
                ["First", "Artist", "Album", ""],
                ["Second", "Artist", "Album", ""],
            ],
        )

    def test_missing_recording_msid_is_not_deduplicated(self) -> None:
        rows = listens_to_rows(
            [
                make_listen(title="First", msid=None),
                make_listen(title="First", msid=None),
            ]
        )
        self.assertEqual(
            rows,
            [
                ["First", "Artist", "Album", ""],
                ["First", "Artist", "Album", ""],
            ],
        )


class OrderingTests(unittest.TestCase):
    def test_newest_first_order_is_preserved(self) -> None:
        listens = [
            make_listen(title="Newest", msid="3", listened_at=300),
            make_listen(title="Middle", msid="2", listened_at=200),
            make_listen(title="Oldest", msid="1", listened_at=100),
        ]

        self.assertEqual(
            [row[0] for row in listens_to_rows(listens)],
            ["Newest", "Middle", "Oldest"],
        )


if __name__ == "__main__":
    unittest.main()
