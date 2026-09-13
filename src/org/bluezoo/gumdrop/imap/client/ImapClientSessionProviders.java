/*
 * ImapClientSessionProviders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap.client;

import org.bluezoo.gumdrop.imap.client.handler.RemoteGreeting;

import java.util.function.Supplier;

/**
 * Factory methods for {@link ImapClientSessionProvider} implementations.
 */
public final class ImapClientSessionProviders {

    private ImapClientSessionProviders() {
    }

    public static ImapClientSessionProvider perSession(
            final Supplier<RemoteGreeting> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new ImapClientSessionProvider() {
            @Override
            public RemoteGreeting openSession() {
                return supplier.get();
            }
        };
    }

}
