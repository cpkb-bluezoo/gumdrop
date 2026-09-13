#!/usr/bin/env python3
"""Workstream C.2.1 — HTTP facade re-exports at protocol root (Option 2).

HttpServer re-export landed in C.1.2 (`http/server/HttpServer` + `http/HttpServer`).
This script documents/regenerates the HttpClient re-export only.

Interface re-exports (HttpRequestHandler, HttpRequest, …) are intentionally
avoided: Java sub-interfaces are not assignable from implementations of the
canonical type in `server/` or `client/` subpackages.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HTTP = ROOT / "src/org/bluezoo/gumdrop/http"
HTTP_CLIENT = HTTP / "client"

HTTP_CLIENT_REEXPORT = """/*
 * HttpClient.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http;

import java.net.InetAddress;

import org.bluezoo.gumdrop.SelectorLoop;

/**
 * Protocol-root re-export of {@link org.bluezoo.gumdrop.http.client.HttpClient}
 * (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.http.client.HttpClient
 * @see docs/NAMING-TAXONOMY.md
 */
public class HttpClient extends org.bluezoo.gumdrop.http.client.HttpClient {

    public HttpClient(String host, int port) {
        super(host, port);
    }

    public HttpClient(SelectorLoop selectorLoop, String host, int port) {
        super(selectorLoop, host, port);
    }

    public HttpClient(InetAddress host, int port) {
        super(host, port);
    }

    public HttpClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        super(selectorLoop, host, port);
    }

    public HttpClient(String path) {
        super(path);
    }

    public HttpClient(SelectorLoop selectorLoop, String path) {
        super(selectorLoop, path);
    }
}
"""


def main() -> None:
    target = HTTP / "HttpClient.java"
    target.write_text(HTTP_CLIENT_REEXPORT, encoding="utf-8")
    print(f"Wrote {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
