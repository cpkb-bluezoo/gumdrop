/*
 * Pop3Client.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.pop3;

import java.net.InetAddress;

import org.bluezoo.gumdrop.SelectorLoop;

/**
 * Protocol-root re-export of {@link org.bluezoo.gumdrop.pop3.client.Pop3Client} (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.pop3.client.Pop3Client
 * @see docs/NAMING-TAXONOMY.md
 */
public class Pop3Client extends org.bluezoo.gumdrop.pop3.client.Pop3Client {

    public Pop3Client(String host, int port) {
        super(host, port);
    }

    public Pop3Client(SelectorLoop selectorLoop, String host, int port) {
        super(selectorLoop, host, port);
    }

    public Pop3Client(InetAddress host, int port) {
        super(host, port);
    }

    public Pop3Client(SelectorLoop selectorLoop, InetAddress host, int port) {
        super(selectorLoop, host, port);
    }

    public Pop3Client(String socketPath) {
        super(socketPath);
    }

    public Pop3Client(SelectorLoop selectorLoop, String socketPath) {
        super(selectorLoop, socketPath);
    }
}
