/*
 * SmtpServerSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
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
 * {@link org.bluezoo.gumdrop.smtp.server.SimpleRelayHandler},
 * {@link org.bluezoo.gumdrop.smtp.server.LocalDeliveryHandler}.
 * Stock providers: {@link SimpleRelaySessionProvider},
 * {@link LocalDeliverySessionProvider}; see {@link SmtpServerSessionProviders}.
 *
 * <p>{@link SmtpServer} implements this interface. Stateless protocols such as
 * HTTP and DNS compose with request/query handlers instead — they do not extend
 * {@link ServerSessionProvider}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerSessionProvider
 * @see web/configuration.html
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
