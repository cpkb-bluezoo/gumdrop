/*
 * SmtpServerSessionProviders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp.server;

import org.bluezoo.gumdrop.TcpListener;

import java.util.function.Supplier;

/**
 * Factory methods for {@link SmtpServerSessionProvider} implementations.
 *
 * @see SmtpServer#compose()
 */
public final class SmtpServerSessionProviders {

    private SmtpServerSessionProviders() {
    }

    /**
     * Returns a stock open-relay session provider (MX forwarding).
     */
    public static SimpleRelaySessionProvider relay() {
        return new SimpleRelaySessionProvider();
    }

    /**
     * Returns a stock local-mailbox delivery session provider.
     */
    public static LocalDeliverySessionProvider localDelivery() {
        return new LocalDeliverySessionProvider();
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
