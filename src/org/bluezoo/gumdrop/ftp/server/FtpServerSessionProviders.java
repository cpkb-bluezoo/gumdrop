/*
 * FtpServerSessionProviders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;
import org.bluezoo.gumdrop.ftp.handler.ClientConnected;

import java.util.function.Supplier;

/**
 * Factory methods for {@link FtpServerSessionProvider} implementations.
 */
public final class FtpServerSessionProviders {

    private FtpServerSessionProviders() {
    }

    /**
     * Returns a provider for local filesystem access (Simple FTP semantics).
     */
    public static FileSystemFtpSessionProvider fileSystem() {
        return new FileSystemFtpSessionProvider();
    }

    /**
     * Wraps a legacy {@link FtpConnectionHandler} supplier.
     */
    public static LegacyConnectionHandlerSessionProvider connectionHandler(
            Supplier<FtpConnectionHandler> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new LegacyConnectionHandlerSessionProvider(supplier);
    }

    public static FtpServerSessionProvider perSession(
            final Supplier<ClientConnected> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new FtpServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return supplier.get();
            }
        };
    }

}
