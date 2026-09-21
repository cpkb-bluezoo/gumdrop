#!/usr/bin/env python3
"""Workstream C.1.3 — Servlet / WebDAV / WebSocket server renames."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

RENAMES = [
    ("WebSocketService", "WebSocketServer"),
    ("ServletService", "ServletServer"),
    ("WebDAVService", "WebdavServer"),
]

SKIP_DIRS = {".git", "build", "build-core", "build-http", "dist", ".cursor"}
SKIP_FILES = {
    "scripts/c13-server-rename.py",
    "src/org/bluezoo/gumdrop/servlet/ServletService.java",
    "src/org/bluezoo/gumdrop/webdav/WebDAVService.java",
    "src/org/bluezoo/gumdrop/websocket/WebSocketService.java",
}

TEXT_SUFFIXES = {
    ".java", ".xml", ".properties", ".md", ".html", ".txt", ".rc", ".json",
}


def should_process(path: Path) -> bool:
    rel = path.relative_to(ROOT).as_posix()
    if rel in SKIP_FILES:
        return False
    for part in path.parts:
        if part in SKIP_DIRS:
            return False
    return path.suffix in TEXT_SUFFIXES or path.name == "build.xml"


def apply_renames(content: str) -> str:
    for old, new in RENAMES:
        content = re.sub(r"\b" + re.escape(old) + r"\b", new, content)
    return content


def main() -> None:
    changed = 0
    for path in ROOT.rglob("*"):
        if not path.is_file() or not should_process(path):
            continue
        original = path.read_text(encoding="utf-8", errors="replace")
        updated = apply_renames(original)
        if updated != original:
            path.write_text(updated, encoding="utf-8")
            changed += 1
    print(f"Updated {changed} files.")


if __name__ == "__main__":
    main()
