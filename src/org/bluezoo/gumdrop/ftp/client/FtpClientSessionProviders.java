/*
 * FtpClientSessionProviders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.client;

import org.bluezoo.gumdrop.ftp.client.handler.RemoteGreeting;

import java.util.function.Supplier;

/**
 * Factory methods for {@link FtpClientSessionProvider} implementations.
 */
public final class FtpClientSessionProviders {

    private FtpClientSessionProviders() {
    }

    /**
     * Returns a provider that supplies a fresh bootstrap handler for each
     * outbound session.
     */
    public static FtpClientSessionProvider perSession(
            final Supplier<RemoteGreeting> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new FtpClientSessionProvider() {
            @Override
            public RemoteGreeting openSession() {
                return supplier.get();
            }
        };
    }

}
