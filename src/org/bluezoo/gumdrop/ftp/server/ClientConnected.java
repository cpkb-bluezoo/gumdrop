/*
 * ClientConnected.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.Endpoint;

/**
 * Entry point handler for new FTP control connections (RFC 959 §4.2).
 *
 * <p>Returned by {@link org.bluezoo.gumdrop.ftp.server.FtpServerSessionProvider}
 * for each accepted connection.
 */
public interface ClientConnected {

    /**
     * Called when the TCP connection is ready (after implicit TLS, if any).
     *
     * @param state operations for accepting or rejecting the session
     * @param endpoint the transport endpoint
     */
    void connected(ConnectedState state, Endpoint endpoint);

    /**
     * Called when the control connection closes for any reason.
     */
    void disconnected();

}
