/*
 * SmtpClientSessionProviders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp.client;

import org.bluezoo.gumdrop.smtp.client.handler.RemoteGreeting;

import java.util.function.Supplier;

/**
 * Factory methods for {@link SmtpClientSessionProvider} implementations.
 *
 * @see SmtpClient#builder()
 */
public final class SmtpClientSessionProviders {

    private SmtpClientSessionProviders() {
    }

    /**
     * Returns a provider that supplies a fresh bootstrap handler for each
     * outbound session.
     */
    public static SmtpClientSessionProvider perSession(
            final Supplier<RemoteGreeting> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new SmtpClientSessionProvider() {
            @Override
            public RemoteGreeting openSession() {
                return supplier.get();
            }
        };
    }

}
