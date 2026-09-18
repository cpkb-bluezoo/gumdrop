#!/usr/bin/env python3
"""Move HttpResponseState into http/server/ (server SPI, not shared client API)."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HTTP = ROOT / "src/org/bluezoo/gumdrop/http"
SERVER = HTTP / "server"
SERVER_PKG = "org.bluezoo.gumdrop.http.server"
TYPE = "HttpResponseState"

SKIP_DIRS = {".git", "build", "node_modules"}
JAVA_ROOTS = [ROOT / "src", ROOT / "test", ROOT / "examples", ROOT / "web"]

IMPORT_OLD = f"import org.bluezoo.gumdrop.http.{TYPE};"
IMPORT_NEW = f"import {SERVER_PKG}.{TYPE};"
IMPORT_RE = re.compile(
    rf"^import org\.bluezoo\.gumdrop\.http\.{re.escape(TYPE)};",
    re.MULTILINE,
)
JAVADOC_OLD = f"org.bluezoo.gumdrop.http.{TYPE}"
JAVADOC_NEW = f"{SERVER_PKG}.{TYPE}"


def rewrite_java_imports(content: str, path: Path) -> str:
    if path.parent == SERVER and path.name == f"{TYPE}.java":
        return content
    return IMPORT_RE.sub(IMPORT_NEW, content)


def rewrite_javadoc_fqcn(content: str) -> str:
    return content.replace(JAVADOC_OLD, JAVADOC_NEW)


def move_file() -> None:
    src = HTTP / f"{TYPE}.java"
    if not src.exists():
        print(f"SKIP missing {src}")
        return
    content = src.read_text(encoding="utf-8")
    content = re.sub(
        r"^package org\.bluezoo\.gumdrop\.http;",
        f"package {SERVER_PKG};",
        content,
        count=1,
        flags=re.MULTILINE,
    )
    marker = "import org.bluezoo.gumdrop.websocket.WebSocketExtension;"
    headers_import = "import org.bluezoo.gumdrop.http.Headers;\n"
    if headers_import.strip() not in content and marker in content:
        content = content.replace(marker, headers_import + marker, 1)
    dest = SERVER / f"{TYPE}.java"
    dest.write_text(content, encoding="utf-8")
    src.unlink()
    print(f"Moved {TYPE}.java -> server/{TYPE}.java")


def rewrite_all() -> None:
    for root in JAVA_ROOTS:
        if not root.is_dir():
            continue
        for path in root.rglob("*.java"):
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            text = path.read_text(encoding="utf-8")
            updated = rewrite_java_imports(text, path)
            updated = rewrite_javadoc_fqcn(updated)
            if updated != text:
                path.write_text(updated, encoding="utf-8")
                print(f"  updated {path.relative_to(ROOT)}")


def update_package_info() -> None:
    http_pkg = HTTP / "package-info.java"
    text = http_pkg.read_text(encoding="utf-8")
    text = text.replace(
        " * org.bluezoo.gumdrop.http.server.HttpResponseState} and {@link\n"
        " * org.bluezoo.gumdrop.http.server.HttpRequestHandler} are shared by all three\n"
        " * versions, so request handlers are written once.",
        " * org.bluezoo.gumdrop.http.server.HttpRequestHandler} and {@link\n"
        " * org.bluezoo.gumdrop.http.server.HttpResponseState} are shared by all three\n"
        " * server versions, so request handlers are written once.",
    )
    text = text.replace(
        " * org.bluezoo.gumdrop.http.server.HttpResponseState#sendInformational}, across",
        " * org.bluezoo.gumdrop.http.server.HttpResponseState#sendInformational}, across",
    )
    http_pkg.write_text(text, encoding="utf-8")

    server_pkg = SERVER / "package-info.java"
    server_pkg.write_text(
        """/**
 * HTTP server-side SPI: listeners, request handlers, response state,
 * authentication, and metrics. Application facades {@link org.bluezoo.gumdrop.http.HttpServer}
 * and {@link org.bluezoo.gumdrop.http.HttpClient} live in the protocol root package.
 *
 * <p>Shared codec types ({@link org.bluezoo.gumdrop.http.Headers},
 * {@link org.bluezoo.gumdrop.http.HttpStatus}, {@link org.bluezoo.gumdrop.http.HttpVersion})
 * remain in {@link org.bluezoo.gumdrop.http}.
 */
package org.bluezoo.gumdrop.http.server;
""",
        encoding="utf-8",
    )


def update_config_aliases() -> None:
    path = ROOT / "src/org/bluezoo/gumdrop/config/ConfigurationParser.java"
    text = path.read_text(encoding="utf-8")
    insert = (
        '        map.put("org.bluezoo.gumdrop.http.HTTPResponseState",\n'
        f'                "{SERVER_PKG}.{TYPE}");\n'
        '        map.put("org.bluezoo.gumdrop.http.HttpResponseState",\n'
        f'                "{SERVER_PKG}.{TYPE}");\n'
    )
    marker = '        map.put("org.bluezoo.gumdrop.http.HTTPListener",'
    if f"{SERVER_PKG}.{TYPE}" not in text and marker in text:
        text = text.replace(marker, insert + marker, 1)
        path.write_text(text, encoding="utf-8")


def main() -> None:
    move_file()
    rewrite_all()
    update_package_info()
    update_config_aliases()
    print("HttpResponseState move complete")


if __name__ == "__main__":
    main()
