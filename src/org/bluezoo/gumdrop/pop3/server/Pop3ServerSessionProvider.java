/*
 * Pop3ServerSessionProvider.java
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

package org.bluezoo.gumdrop.pop3.server;

import org.bluezoo.gumdrop.ServerSessionProvider;

/**
 * POP3 server composition SPI — mints a staged handler pipeline per accepted
 * control connection.
 *
 * <p>Stock provider: {@link MailboxStorePop3SessionProvider}.
 *
 * @see ServerSessionProvider
 * @see web/configuration.html
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Pop3ServerSessionProvider
        extends ServerSessionProvider<ClientConnected> {

    default void start() {
    }

    default void stop() {
    }

}
