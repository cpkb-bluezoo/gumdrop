/*
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
