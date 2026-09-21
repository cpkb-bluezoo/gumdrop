#!/usr/bin/env python3
"""Workstream C.1.2 — bulk HTTP acronym renames (HTTP* -> Http*)."""

from __future__ import annotations

import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Longest legacy names first when applying replacements.
RENAMES = [
    ("HTTP3WebSocketListener", "Http3WebSocketListener"),
    ("HTTP3ClientHandler", "Http3ClientHandler"),
    ("HTTP3ServerHandler", "Http3ServerHandler"),
    ("HTTPClientProtocolHandler", "HttpClientProtocolHandler"),
    ("HTTPRequestHandlerFactory", "HttpRequestHandlerFactory"),
    ("HTTPAuthenticationMethods", "HttpAuthenticationMethods"),
    ("HTTPAuthenticationProvider", "HttpAuthenticationProvider"),
    ("DefaultHTTPAuthenticationProvider", "DefaultHttpAuthenticationProvider"),
    ("DefaultHTTPResponseHandler", "DefaultHttpResponseHandler"),
    ("DefaultHTTPRequestHandler", "DefaultHttpRequestHandler"),
    ("HTTPClientConnectionOps", "HttpClientConnectionOps"),
    ("HTTPClientLineLexer", "HttpClientLineLexer"),
    ("HTTPResponseHandler", "HttpResponseHandler"),
    ("HTTPClientHandler", "HttpClientHandler"),
    ("HTTPProtocolHandler", "HttpProtocolHandler"),
    ("HTTPResponseState", "HttpResponseState"),
    ("HTTPRequestHandler", "HttpRequestHandler"),
    ("HTTPServerMetrics", "HttpServerMetrics"),
    ("HTTPMethodSafety", "HttpMethodSafety"),
    ("HTTPConnectionLike", "HttpConnectionLike"),
    ("HTTP3Listener", "Http3Listener"),
    ("HTTPDateFormat", "HttpDateFormat"),
    ("HTTPConstants", "HttpConstants"),
    ("HTTPDateCache", "HttpDateCache"),
    ("HTTPLineLexer", "HttpLineLexer"),
    ("HTTPPrincipal", "HttpPrincipal"),
    ("HTTPListener", "HttpListener"),
    ("HTTPResponse", "HttpResponse"),
    ("HTTPRequest", "HttpRequest"),
    ("HTTPClient", "HttpClient"),
    ("HTTPStatus", "HttpStatus"),
    ("HTTPUtils", "HttpUtils"),
    ("HTTPVersion", "HttpVersion"),
    ("HTTPStream", "HttpStream"),
]

SKIP_DIRS = {".git", "build", "build-core", "build-http", "dist", ".cursor"}
SKIP_FILES = {
    "scripts/c12-http-rename.py",
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


def safe_rename(path: Path, new_name: str) -> None:
    target = path.with_name(new_name)
    if path.name == new_name:
        return
    if target.exists():
        # Case-only rename on case-insensitive volumes (macOS default).
        tmp = path.with_name(f".__c12_rename__.{path.name}")
        path.rename(tmp)
        tmp.rename(target)
    else:
        path.rename(target)


def rename_java_files() -> None:
    for path in list(ROOT.rglob("*.java")):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        for old, new in RENAMES:
            if path.name == old + ".java":
                safe_rename(path, new + ".java")
                break


def main() -> None:
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "--files-only":
        rename_java_files()
        print("Renamed Java source files.")
        return
    changed = 0
    for path in ROOT.rglob("*"):
        if not path.is_file() or not should_process(path):
            continue
        original = path.read_text(encoding="utf-8", errors="replace")
        updated = apply_renames(original)
        if updated != original:
            path.write_text(updated, encoding="utf-8")
            changed += 1
    rename_java_files()
    print(f"Updated {changed} files; renamed Java types.")


if __name__ == "__main__":
    main()
