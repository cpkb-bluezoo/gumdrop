/*
 * SmtpServerSessionProviders.java
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

import org.bluezoo.gumdrop.TcpListener;

import java.util.function.Supplier;

/**
 * Factory methods for {@link SmtpServerSessionProvider} implementations.
 *
 * @see SmtpServer#compose()
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SmtpServerSessionProviders {

    private SmtpServerSessionProviders() {
    }

    /**
     * Returns a stock open-relay session provider (MX forwarding).
     */
    public static SimpleRelaySessionProvider relay() {
        return new SimpleRelaySessionProvider();
    }

    /**
     * Returns a stock local-mailbox delivery session provider.
     */
    public static LocalDeliverySessionProvider localDelivery() {
        return new LocalDeliverySessionProvider();
    }

    /**
     * Returns a provider that creates a fresh session pipeline for each
     * accepted connection.
     */
    public static SmtpServerSessionProvider perSession(
            final Supplier<ClientConnected> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new SmtpServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return supplier.get();
            }
        };
    }

}
