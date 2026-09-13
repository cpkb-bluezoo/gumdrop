/*
 * SmtpClient.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp;

import java.net.InetAddress;

import org.bluezoo.gumdrop.SelectorLoop;

/**
 * Protocol-root re-export of {@link org.bluezoo.gumdrop.smtp.client.SmtpClient} (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.smtp.client.SmtpClient
 * @see docs/NAMING-TAXONOMY.md
 */
public class SmtpClient extends org.bluezoo.gumdrop.smtp.client.SmtpClient {

    public SmtpClient(String host, int port) {
        super(host, port);
    }

    public SmtpClient(SelectorLoop selectorLoop, String host, int port) {
        super(selectorLoop, host, port);
    }

    public SmtpClient(InetAddress host, int port) {
        super(host, port);
    }

    public SmtpClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        super(selectorLoop, host, port);
    }

    public SmtpClient(String socketPath) {
        super(socketPath);
    }

    public SmtpClient(SelectorLoop selectorLoop, String socketPath) {
        super(selectorLoop, socketPath);
    }
}
