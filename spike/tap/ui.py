#!/usr/bin/env python3
"""Print the centre of the transport Play control from a uiautomator dump.

Exits 0 and prints "x y" when found, exit 1 otherwise.
"""

from __future__ import annotations

import re
import sys

NODE = re.compile(r"<node [^>]*>")


def centre(bounds: str) -> tuple[int, int] | None:
    match = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not match:
        return None
    x1, y1, x2, y2 = map(int, match.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def main() -> int:
    if len(sys.argv) != 2:
        return 2

    try:
        with open(sys.argv[1], encoding="utf-8") as handle:
            xml = handle.read()
    except OSError:
        return 1

    for node in NODE.findall(xml):
        if not re.search(r'(?:text|content-desc)="Play"', node):
            continue
        match = re.search(r'bounds="([^"]+)"', node)
        if not match:
            continue
        point = centre(match.group(1))
        if point:
            print(point[0], point[1])
            return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
