/*
 * FtpClient.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp;

import java.net.InetAddress;

import org.bluezoo.gumdrop.SelectorLoop;

/**
 * Protocol-root re-export of {@link org.bluezoo.gumdrop.ftp.client.FtpClient} (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.ftp.client.FtpClient
 * @see docs/NAMING-TAXONOMY.md
 */
public class FtpClient extends org.bluezoo.gumdrop.ftp.client.FtpClient {

    public FtpClient(String host, int port) {
        super(host, port);
    }
    public FtpClient(SelectorLoop selectorLoop, String host, int port) {
        super(selectorLoop, host, port);
    }
    public FtpClient(InetAddress host, int port) {
        super(host, port);
    }
    public FtpClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        super(selectorLoop, host, port);
    }
    public FtpClient(String socketPath) {
        super(socketPath);
    }
    public FtpClient(SelectorLoop selectorLoop, String socketPath) {
        super(selectorLoop, socketPath);
    }
}
