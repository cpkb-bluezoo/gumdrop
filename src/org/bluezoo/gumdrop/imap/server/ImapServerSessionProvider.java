/*
 * ImapServerSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap.server;

import org.bluezoo.gumdrop.ServerSessionProvider;

/**
 * IMAP server composition SPI — mints a staged handler pipeline per accepted
 * control connection.
 *
 * <p>Stock provider: {@link MailboxStoreImapSessionProvider}.
 *
 * @see ServerSessionProvider
 * @see docs/COMPOSITION.md
 */
public interface ImapServerSessionProvider
        extends ServerSessionProvider<ClientConnected> {

    /**
     * Initialises shared resources before listeners accept connections.
     */
    default void start() {
    }

    /**
     * Releases resources after all listeners have stopped.
     */
    default void stop() {
    }

}
