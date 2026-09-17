/*
 * FtpServerSessionProviders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;

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
     * Returns a provider for anonymous, read-only public file distribution.
     */
    public static AnonymousFtpSessionProvider anonymous() {
        return new AnonymousFtpSessionProvider();
    }

    /**
     * Returns a provider with role-based access control.
     */
    public static RoleBasedFtpSessionProvider roleBased() {
        return new RoleBasedFtpSessionProvider();
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
