#!/usr/bin/env python3
"""Compatibility entry point for the server administration commands.

Prefer ``python -m server <command>``. This wrapper keeps existing commands
such as ``python server/admin.py init`` working.
"""

from __future__ import annotations

import sys
from pathlib import Path

if __package__ in (None, ""):  # allow `python server/admin.py`
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from server.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
