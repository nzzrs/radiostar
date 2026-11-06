# ListenBrainz CSV export

Download a user's ListenBrainz listens as a CSV that RadioStar can import.

The exporter uses only the Python 3.12 standard library.

## Usage

```sh
python3 scripts/listenbrainz_export.py \
  --user <listenbrainz_user> \
  --out listens.csv \
  --count 5000
```

`--user` may be omitted when `LB_USER` is set:

```sh
export LB_USER=<listenbrainz_user>
python3 scripts/listenbrainz_export.py --out listens.csv
```

`LB_TOKEN` is optional. When it is set and non-empty, it is sent as a
ListenBrainz API token. Without it, the export only works for public listens.
Tokens are never written to the CSV or printed.

`--count` defaults to `5000`. The exporter paginates at up to 1000 listens per
request and stops after writing that many rows, or when there are no more
listens.

## Output columns

The output always starts with this header:

```csv
Title,Artist,Album,YouTube Video ID
```

| Column | Meaning |
| --- | --- |
| `Title` | ListenBrainz track title |
| `Artist` | ListenBrainz artist name |
| `Album` | ListenBrainz release name, or empty if missing |
| `YouTube Video ID` | `youtube_id`, then `video_id` from ListenBrainz metadata, or empty |

Rows are written newest first. Duplicate listens with the same
`recording_msid` are written once, and rows without a title are skipped.

## Privacy

The output CSV is private listening history. Keep it out of git. The repository
ignores `scripts/*.csv`; do not force-add an export or commit listening data.
