/*
 * Pop3ServerSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.pop3.server;

import org.bluezoo.gumdrop.ServerSessionProvider;

/**
 * POP3 server composition SPI — mints a staged handler pipeline per accepted
 * control connection.
 *
 * <p>Stock provider: {@link MailboxStorePop3SessionProvider}.
 *
 * @see ServerSessionProvider
 * @see docs/COMPOSITION.md
 */
public interface Pop3ServerSessionProvider
        extends ServerSessionProvider<ClientConnected> {

    default void start() {
    }

    default void stop() {
    }

}
