/*
 * SmtpServerSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp.server;

import org.bluezoo.gumdrop.ServerSessionProvider;

/**
 * SMTP server composition SPI — mints a staged handler pipeline per accepted
 * control connection.
 *
 * <p>Implementations return an object that implements {@link ClientConnected}
 * and subsequent staged server handler interfaces ({@link
 * org.bluezoo.gumdrop.smtp.server.HelloHandler}, {@link
 * org.bluezoo.gumdrop.smtp.server.MailFromHandler}, …). Stock examples:
 * {@link org.bluezoo.gumdrop.smtp.SimpleRelayHandler},
 * {@link org.bluezoo.gumdrop.smtp.LocalDeliveryHandler}.
 * Stock providers: {@link SimpleRelaySessionProvider},
 * {@link LocalDeliverySessionProvider}; see {@link SmtpServerSessionProviders}.
 *
 * <p>{@link SmtpServer} implements this interface. Stateless protocols such as
 * HTTP and DNS compose with request/query handlers instead — they do not extend
 * {@link ServerSessionProvider}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerSessionProvider
 * @see docs/COMPOSITION.md
 */
public interface SmtpServerSessionProvider
        extends ServerSessionProvider<ClientConnected> {

    /**
     * Initialises shared resources before listeners accept connections.
     *
     * <p>Called from {@link SmtpServer#start()}. Default: no-op.
     */
    default void start() {
    }

    /**
     * Releases resources after all listeners have stopped.
     *
     * <p>Called from {@link SmtpServer#stop()}. Default: no-op.
     */
    default void stop() {
    }

}
