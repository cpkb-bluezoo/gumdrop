/*
 * Pop3ClientSessionProviders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.pop3.client;

import org.bluezoo.gumdrop.pop3.client.handler.RemoteGreeting;

import java.util.function.Supplier;

/**
 * Factory methods for {@link Pop3ClientSessionProvider} implementations.
 */
public final class Pop3ClientSessionProviders {

    private Pop3ClientSessionProviders() {
    }

    public static Pop3ClientSessionProvider perSession(
            final Supplier<RemoteGreeting> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new Pop3ClientSessionProvider() {
            @Override
            public RemoteGreeting openSession() {
                return supplier.get();
            }
        };
    }

}
