/*
 * Pop3ServerSessionProviders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.pop3.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;

import java.util.function.Supplier;

/**
 * Factory methods for {@link Pop3ServerSessionProvider} implementations.
 */
public final class Pop3ServerSessionProviders {

    private Pop3ServerSessionProviders() {
    }

    /**
     * Returns a {@link MailboxStorePop3SessionProvider} without a factory.
     */
    public static MailboxStorePop3SessionProvider mailbox() {
        return new MailboxStorePop3SessionProvider();
    }

    /**
     * Returns a ready-to-use provider with the given mailbox backing store.
     */
    public static MailboxStorePop3SessionProvider mailbox(MailboxFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        return new MailboxStorePop3SessionProvider().mailboxFactory(factory);
    }

    public static Pop3ServerSessionProvider perSession(
            final Supplier<ClientConnected> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new Pop3ServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return supplier.get();
            }
        };
    }

}
