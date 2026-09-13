#!/usr/bin/env python3
"""Workstream C.2.5 — move HTTP server SPI into http/server/."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HTTP = ROOT / "src/org/bluezoo/gumdrop/http"
SERVER = HTTP / "server"
SERVER_PKG = "org.bluezoo.gumdrop.http.server"

# Canonical public types moving from http root → http.server
MOVE_FILES = [
    "HttpRequestHandler.java",
    "HttpRequestHandlerFactory.java",
    "DefaultHttpRequestHandler.java",
    "HttpAuthenticationProvider.java",
    "HttpAuthenticationMethods.java",
    "DefaultHttpAuthenticationProvider.java",
    "HttpPrincipal.java",
    "HttpConnectionLike.java",
    "HttpLineLexer.java",
    "HttpProtocolHandler.java",
    "HttpServerMetrics.java",
    "HttpListener.java",
    "ConnectIpRequestHandler.java",
    "ConnectUdpRequestHandler.java",
    "ConnectUdpRelay.java",
    "ConnectIpPolicy.java",
    "ConnectUdpPolicy.java",
    "ConnectIpSession.java",
    "IpPacketHandler.java",
    "Stream.java",
]

# Simple names for import rewriting (longest first to avoid partial matches)
TYPE_NAMES = sorted(
    {Path(name).stem for name in MOVE_FILES},
    key=len,
    reverse=True,
)

SKIP_DIRS = {".git", "build", "node_modules"}
JAVA_ROOTS = [
    ROOT / "src",
    ROOT / "test",
    ROOT / "examples",
    ROOT / "web",
]

IMPORT_RE = re.compile(
    r"^import org\.bluezoo\.gumdrop\.http\.(" + "|".join(re.escape(n) for n in TYPE_NAMES) + r");",
    re.MULTILINE,
)

def rewrite_java_imports(content: str, path: Path) -> str:
    if path.parent == SERVER:
        return content

    def repl(match: re.Match[str]) -> str:
        typ = match.group(1)
        return f"import {SERVER_PKG}.{typ};"

    return IMPORT_RE.sub(repl, content)


def rewrite_all_imports() -> None:
    for root in JAVA_ROOTS:
        if not root.is_dir():
            continue
        for path in root.rglob("*.java"):
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            text = path.read_text(encoding="utf-8")
            updated = rewrite_java_imports(text, path)
            if updated != text:
                path.write_text(updated, encoding="utf-8")


def change_package(content: str) -> str:
    return re.sub(
        r"^package org\.bluezoo\.gumdrop\.http;",
        f"package {SERVER_PKG};",
        content,
        count=1,
        flags=re.MULTILINE,
    )


def strip_same_package_imports(content: str) -> str:
    for name in TYPE_NAMES:
        content = re.sub(
            rf"^import org\.bluezoo\.gumdrop\.http\.{re.escape(name)};\n",
            "",
            content,
            flags=re.MULTILINE,
        )
    return content


def move_files() -> None:
    SERVER.mkdir(parents=True, exist_ok=True)
    for name in MOVE_FILES:
        src = HTTP / name
        if not src.exists():
            print(f"SKIP missing {name}")
            continue
        content = src.read_text(encoding="utf-8")
        if f"package {SERVER_PKG};" in content:
            print(f"SKIP already moved {name}")
            continue
        content = change_package(content)
        content = strip_same_package_imports(content)
        dest = SERVER / name
        dest.write_text(content, encoding="utf-8")
        src.unlink()
        print(f"Moved {name} -> server/{name}")


def update_server_package_info() -> None:
    (SERVER / "package-info.java").write_text(
        """/**
 * HTTP server-side SPI: listeners, request handlers, authentication, and
 * metrics. Facade entry types ({@link org.bluezoo.gumdrop.http.HttpServer},
 * {@link org.bluezoo.gumdrop.http.HttpClient}) are re-exported at the
 * protocol root for ergonomics (§C.2 Option 2).
 *
 * <p>Shared codec and transport types ({@link org.bluezoo.gumdrop.http.Headers},
 * {@link org.bluezoo.gumdrop.http.server.Stream}) remain in
 * {@link org.bluezoo.gumdrop.http.server}.
 */
package org.bluezoo.gumdrop.http.server;
""",
        encoding="utf-8",
    )


def update_config_aliases() -> None:
    path = ROOT / "src/org/bluezoo/gumdrop/config/ConfigurationParser.java"
    text = path.read_text(encoding="utf-8")
    replacements = {
        '"org.bluezoo.gumdrop.http.HTTPListener",\n'
        '                "org.bluezoo.gumdrop.http.HttpListener"':
            '"org.bluezoo.gumdrop.http.HTTPListener",\n'
            '                "org.bluezoo.gumdrop.http.server.HttpListener"',
        '"org.bluezoo.gumdrop.http.HttpListener",\n'
        '                "org.bluezoo.gumdrop.http.HttpListener"':
            '"org.bluezoo.gumdrop.http.HttpListener",\n'
            '                "org.bluezoo.gumdrop.http.server.HttpListener"',
    }
    for old, new in replacements.items():
        if old in text:
            text = text.replace(old, new, 1)
    # Add legacy aliases for old root FQCNs if not present
    insert_marker = '        map.put("org.bluezoo.gumdrop.http.h3.HTTP3Listener",'
    if insert_marker in text and "http.server.HttpListener" not in text.split(insert_marker)[0][-500:]:
        pass
    if 'org.bluezoo.gumdrop.http.server.HttpListener' not in text:
        text = text.replace(
            insert_marker,
            '        map.put("org.bluezoo.gumdrop.http.HttpListener",\n'
            '                "org.bluezoo.gumdrop.http.server.HttpListener");\n'
            '        map.put("org.bluezoo.gumdrop.http.HTTPListener",\n'
            '                "org.bluezoo.gumdrop.http.server.HttpListener");\n'
            + insert_marker,
            1,
        )
    path.write_text(text, encoding="utf-8")


def main() -> None:
    move_files()
    rewrite_all_imports()
    update_server_package_info()
    update_config_aliases()
    print("C.2.5 file moves and import rewrite complete")


if __name__ == "__main__":
    main()
