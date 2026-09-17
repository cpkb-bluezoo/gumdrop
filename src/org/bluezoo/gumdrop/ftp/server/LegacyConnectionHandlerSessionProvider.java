/*
 * LegacyConnectionHandlerSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;

import java.util.function.Supplier;

/**
 * Session provider that wraps a legacy {@link FtpConnectionHandler} per
 * connection.
 */
public final class LegacyConnectionHandlerSessionProvider
        implements FtpServerSessionProvider {

    private final Supplier<FtpConnectionHandler> supplier;

    LegacyConnectionHandlerSessionProvider(
            Supplier<FtpConnectionHandler> supplier) {
        this.supplier = supplier;
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        return new LegacyConnectionHandlerAdapter(supplier.get());
    }

}
