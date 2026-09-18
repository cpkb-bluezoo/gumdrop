/*
 * LegacyConnectionHandlerAdapter.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;
import org.bluezoo.gumdrop.ftp.FtpConnectionMetadata;

/**
 * Adapts a legacy {@link FtpConnectionHandler} to {@link ClientConnected}.
 *
 * <p>Used by {@link org.bluezoo.gumdrop.ftp.server.LegacyConnectionHandlerSessionProvider}
 * during migration. The protocol handler unwraps the delegate and continues to
 * use the legacy callback interface until the staged path is fully wired.
 */
public final class LegacyConnectionHandlerAdapter implements ClientConnected {

    private final FtpConnectionHandler delegate;

    public LegacyConnectionHandlerAdapter(FtpConnectionHandler delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        this.delegate = delegate;
    }

    /**
     * Returns the wrapped legacy handler.
     */
    public FtpConnectionHandler getDelegate() {
        return delegate;
    }

    /**
     * Unwraps a session pipeline entry to a legacy handler when possible.
     */
    public static FtpConnectionHandler unwrap(ClientConnected session) {
        if (session instanceof LegacyConnectionHandlerAdapter) {
            return ((LegacyConnectionHandlerAdapter) session).getDelegate();
        }
        return null;
    }

    @Override
    public void connected(ConnectedState state, Endpoint endpoint) {
        // Legacy handlers are driven by FtpProtocolHandler directly.
    }

    @Override
    public void disconnected() {
        // Legacy handlers receive disconnected(metadata) from the protocol handler.
    }

    /**
     * @deprecated for protocol use only — supplies metadata to legacy callbacks
     */
    @Deprecated
    public FtpConnectionHandler getConnectionHandler() {
        return delegate;
    }

}
