#!/usr/bin/env python3
"""Expand abbreviated Java file headers to the full CONTRIBUTING.md LGPL block."""

from __future__ import annotations

import re
import sys
from pathlib import Path

MARKER = "You should have received a copy of the GNU Lesser General Public License"

COPYRIGHT_RE = re.compile(
    r"Copyright\s*\(C\)\s*(\d{4}(?:\s*,\s*\d{4})*)\s+Chris Burdess",
    re.IGNORECASE,
)

TREES = [
    "src/org/bluezoo/gumdrop",
    "src/jakarta",
    "src/org/bluezoo",
    "test/junit/src",
    "test/integration/src",
    # examples/ intentionally excluded — keep short headers for readability
]


def full_header(filename: str, copyright_line: str) -> str:
    return f"""/*
 * {filename}
 * {copyright_line}
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */
"""


def copyright_line(text: str, filename: str) -> str:
    head = text[:800]
    m = COPYRIGHT_RE.search(head)
    if m:
        return f"Copyright (C) {m.group(1)} Chris Burdess"
    return "Copyright (C) 2026 Chris Burdess"


def strip_leading_block_comment(text: str) -> tuple[str | None, str]:
    stripped = text.lstrip()
    if not stripped.startswith("/*"):
        return None, text
    end = stripped.find("*/")
    if end < 0:
        return None, text
    block = stripped[: end + 2]
    rest = stripped[end + 2 :]
    if rest.startswith("\r\n"):
        rest = rest[2:]
    elif rest.startswith("\n"):
        rest = rest[1:]
    return block, rest


def fix_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if MARKER in text[:4000]:
        return False

    filename = path.name
    header = full_header(filename, copyright_line(text, filename))
    old_block, rest = strip_leading_block_comment(text)
    if old_block is not None:
        path.write_text(header + "\n" + rest.lstrip("\n"), encoding="utf-8")
    else:
        path.write_text(header + "\n" + text.lstrip("\n"), encoding="utf-8")
    return True


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    changed = 0
    for rel in TREES:
        base = root / rel
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            if fix_file(path):
                changed += 1
                print(path.relative_to(root))
    print(f"Updated {changed} file(s).", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
