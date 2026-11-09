#!/usr/bin/env python3
"""CLAP text embeddings for cold candidates.

The phone cannot hold the text tower next to the audio tower, so the laptop
embeds `artist - title` strings and the phone caches the 512-d vectors. Both
towers share one space, so an unplayed candidate becomes comparable to a
played track's audio embedding.

    python server/text_embedder.py --text "Lenin Ramirez - Todo Lo Fue" ...
    python server/text_embedder.py --file candidates.txt --json

Checkpoint: the same laion_clap file used for the audio tower
(`630k-audioset-best.pt`), which contains both towers.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from pathlib import Path

import numpy as np

try:  # package import and direct script execution both work
    from .config import DEFAULT_CACHE_PATH
except ImportError:  # pragma: no cover - script execution path
    from config import DEFAULT_CACHE_PATH  # type: ignore

DEFAULT_CHECKPOINT: Path | None = None
CACHE_DB = DEFAULT_CACHE_PATH

PROMPTS = (
    "{artist} - {title}",
    "{title} by {artist}",
    "{artist} {title} official audio",
    "{artist} {title} studio recording",
)


class TextEmbedder:
    def __init__(
        self,
        checkpoint: Path | None = DEFAULT_CHECKPOINT,
        cache_path: Path | str | None = None,
    ) -> None:
        import laion_clap

        if checkpoint is not None and not checkpoint.exists():
            raise FileNotFoundError(f"checkpoint not found: {checkpoint}")
        self._module = laion_clap.CLAP_Module(enable_fusion=False, amodel="HTSAT-tiny")
        self._module.load_ckpt(ckpt=str(checkpoint) if checkpoint is not None else None)
        self._module.eval()

        self.cache_path = Path(cache_path) if cache_path is not None else CACHE_DB
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        # The HTTP server serializes calls with one lock but handles requests on
        # worker threads, so the connection must accept use from any thread.
        self._db = sqlite3.connect(self.cache_path, check_same_thread=False)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS vectors (key TEXT PRIMARY KEY, vector BLOB NOT NULL)"
        )
        self._db.commit()

    def close(self) -> None:
        self._db.close()

    @staticmethod
    def key(artist: str, title: str) -> str:
        material = f"{artist.strip().lower()}\u0000{title.strip().lower()}"
        return hashlib.sha1(material.encode("utf-8")).hexdigest()

    @staticmethod
    def text_key(text: str) -> str:
        material = f"text\u0000{text.strip().lower()}"
        return hashlib.sha1(material.encode("utf-8")).hexdigest()

    def embed(self, artist: str, title: str) -> np.ndarray:
        cache_key = self.key(artist, title)
        row = self._db.execute(
            "SELECT vector FROM vectors WHERE key = ?", (cache_key,)
        ).fetchone()
        if row is not None:
            return np.frombuffer(row[0], dtype=np.float32)

        prompts = [template.format(artist=artist, title=title) for template in PROMPTS]
        averaged = self._module.get_text_embedding(prompts).mean(axis=0)
        averaged = averaged / max(float(np.linalg.norm(averaged)), 1e-9)

        self._db.execute(
            "INSERT OR REPLACE INTO vectors (key, vector) VALUES (?, ?)",
            (cache_key, averaged.astype(np.float32).tobytes()),
        )
        self._db.commit()
        return averaged.astype(np.float32)

    def embed_text(self, text: str) -> np.ndarray:
        """Embed a free-text prompt in the same CLAP space as track metadata."""
        cache_key = self.text_key(text)
        row = self._db.execute(
            "SELECT vector FROM vectors WHERE key = ?", (cache_key,)
        ).fetchone()
        if row is not None:
            return np.frombuffer(row[0], dtype=np.float32)

        vector = self._module.get_text_embedding([text.strip()])[0]
        vector = vector / max(float(np.linalg.norm(vector)), 1e-9)
        vector = vector.astype(np.float32)
        self._db.execute(
            "INSERT OR REPLACE INTO vectors (key, vector) VALUES (?, ?)",
            (cache_key, vector.tobytes()),
        )
        self._db.commit()
        return vector


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--text", action="append", default=[], help="artist - title")
    parser.add_argument("--file", type=Path, help="one 'artist - title' per line")
    parser.add_argument("--checkpoint", type=Path, default=None)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    lines = list(args.text)
    if args.file:
        lines += [
            line.strip() for line in args.file.read_text().splitlines() if line.strip()
        ]
    if not lines:
        parser.error("pass --text or --file")

    embedder = TextEmbedder(args.checkpoint)
    results = []
    for line in lines:
        artist, _, title = line.partition(" - ")
        vector = embedder.embed(artist, title)
        results.append(
            {"line": line, "dim": int(vector.size), "vector": vector.tolist()}
        )

    if args.json:
        print(json.dumps(results))
    else:
        for item in results:
            print(f"{item['line']}: dim={item['dim']} head={item['vector'][:3]}")

    matrix = np.array([item["vector"] for item in results])
    similarity = matrix @ matrix.T
    print("\npairwise cosine:")
    for i, row in enumerate(lines):
        scores = " ".join(f"{similarity[i, j]:.3f}" for j in range(len(lines)))
        print(f"  {row[:40]:40s} {scores}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
