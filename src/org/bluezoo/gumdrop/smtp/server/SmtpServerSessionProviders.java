/*
 * SmtpServerSessionProviders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;

import java.util.function.Supplier;

/**
 * Factory methods for {@link SmtpServerSessionProvider} implementations.
 *
 * @see SmtpServer#builder()
 */
public final class SmtpServerSessionProviders {

    private SmtpServerSessionProviders() {
    }

    /**
     * Returns a provider that creates a fresh session pipeline for each
     * accepted connection.
     */
    public static SmtpServerSessionProvider perSession(
            final Supplier<ClientConnected> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new SmtpServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return supplier.get();
            }
        };
    }

}
