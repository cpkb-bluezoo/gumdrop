#!/usr/bin/env python3
"""Workstream C.2.1 — HTTP facades at protocol root.

HttpServer and HttpClient are canonical types in org.bluezoo.gumdrop.http.
Server SPI lives in http/server/; client SPI in http/client/.
Legacy XML class names map via ConfigurationParser (HTTPService, HTTPClient, …).

Interface re-exports at the protocol root are intentionally avoided for
handler types: Java sub-interfaces are not assignable from implementations
in subpackages.
"""

from __future__ import annotations

print("HttpServer and HttpClient are canonical at org.bluezoo.gumdrop.http — no re-export files.")
