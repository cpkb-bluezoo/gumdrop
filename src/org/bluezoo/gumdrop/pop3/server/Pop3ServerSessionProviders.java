/*
 * Pop3ServerSessionProviders.java
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

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;

import java.util.function.Supplier;

/**
 * Factory methods for {@link Pop3ServerSessionProvider} implementations.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
