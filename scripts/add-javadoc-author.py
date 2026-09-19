#!/usr/bin/env python3
"""Add @author to top-level type Javadoc when missing (CONTRIBUTING.md)."""

from __future__ import annotations

import re
import sys
from pathlib import Path

AUTHOR_LINE = " * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>\n"

TOP_LEVEL_TYPE = re.compile(
    r"^(?:public |)(?:abstract |final |)(?:class|interface|enum) \w+",
    re.MULTILINE,
)

TREES = [
    "src/org/bluezoo/gumdrop",
    "test/junit/src",
    "test/integration/src",
]


def fix_content(text: str) -> tuple[str, bool]:
    if "@author" in text:
        return text, False
    match = TOP_LEVEL_TYPE.search(text)
    if not match:
        return text, False
    type_pos = match.start()
    before = text[:type_pos]
    jdoc_end = before.rfind("*/")
    if jdoc_end >= 0:
        jdoc_start = before.rfind("/**", 0, jdoc_end)
        if jdoc_start >= 0:
            block = before[jdoc_start : jdoc_end + 2]
            if block.strip().startswith("/**") and "@author" not in block:
                tail = before[jdoc_end:]
                if tail.startswith("*/"):
                    tail = " " + tail
                new_before = before[:jdoc_end] + AUTHOR_LINE + tail
                return new_before + text[type_pos:], True
    new_before = before + "/**\n" + AUTHOR_LINE + " */\n"
    return new_before + text[type_pos:], True


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    changed = 0
    for rel in TREES:
        base = root / rel
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            if path.name == "package-info.java":
                continue
            original = path.read_text(encoding="utf-8")
            updated, did = fix_content(original)
            if did:
                path.write_text(updated, encoding="utf-8")
                changed += 1
    print(f"Updated {changed} file(s).", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
