/*
 * ImapServerSessionProviders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.imap.handler.ClientConnected;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;

import java.util.function.Supplier;

/**
 * Factory methods for {@link ImapServerSessionProvider} implementations.
 */
public final class ImapServerSessionProviders {

    private ImapServerSessionProviders() {
    }

    /**
     * Returns a {@link MailboxStoreImapSessionProvider} without a factory.
     *
     * <p>Configure {@link MailboxStoreImapSessionProvider#mailboxFactory(MailboxFactory)}
     * before {@link ImapServer#start()}, or pass a factory to
     * {@link #mailbox(MailboxFactory)}.
     */
    public static MailboxStoreImapSessionProvider mailbox() {
        return new MailboxStoreImapSessionProvider();
    }

    /**
     * Returns a ready-to-use provider with the given mailbox backing store.
     */
    public static MailboxStoreImapSessionProvider mailbox(MailboxFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        return new MailboxStoreImapSessionProvider().mailboxFactory(factory);
    }

    public static ImapServerSessionProvider perSession(
            final Supplier<ClientConnected> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new ImapServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return supplier.get();
            }
        };
    }

}
