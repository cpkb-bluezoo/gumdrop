/*
 * ImapClient.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import java.net.InetAddress;

import org.bluezoo.gumdrop.SelectorLoop;

/**
 * Protocol-root re-export of {@link org.bluezoo.gumdrop.imap.client.ImapClient} (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.imap.client.ImapClient
 * @see docs/NAMING-TAXONOMY.md
 */
public class ImapClient extends org.bluezoo.gumdrop.imap.client.ImapClient {

    public ImapClient(String host, int port) {
        super(host, port);
    }

    public ImapClient(SelectorLoop selectorLoop, String host, int port) {
        super(selectorLoop, host, port);
    }

    public ImapClient(InetAddress host, int port) {
        super(host, port);
    }

    public ImapClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        super(selectorLoop, host, port);
    }

    public ImapClient(String socketPath) {
        super(socketPath);
    }

    public ImapClient(SelectorLoop selectorLoop, String socketPath) {
        super(selectorLoop, socketPath);
    }
}
