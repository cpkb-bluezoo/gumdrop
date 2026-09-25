/*
 * Amqp1ConnectionReady.java
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

package org.bluezoo.gumdrop.amqp1.client;

import java.util.List;

import org.bluezoo.gumdrop.ClientHandler;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;

/**
 * Entry point for an AMQP 1.0 client connection: extends the shared
 * {@link ClientHandler} connection lifecycle with the server's SASL
 * mechanism announcement.
 *
 * <p>The client always negotiates SASL first (core specification 5.3.2
 * and the SASL profile), so the first protocol-level event the
 * application sees is {@link #handleSaslMechanisms}. Brokers that do not
 * require authentication typically still offer {@code ANONYMOUS}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1ConnectionReady extends ClientHandler {

    /**
     * The server announced the SASL mechanisms it supports and is
     * waiting for the client to choose one.
     *
     * @param mechanisms the mechanism names, most-preferred first
     * @param handshake used to authenticate with one of them
     */
    void handleSaslMechanisms(List<String> mechanisms, Amqp1SaslHandshake handshake);

    /**
     * The connection was closed with the AMQP {@code close} performative:
     * either the server closed it (an unsolicited close, or a rejection
     * such as an unknown virtual host), or the server acknowledged a
     * close requested with {@link Amqp1Connection#close}.
     *
     * @param error the error the peer reported, or {@code null} for a
     *      normal close
     */
    void onConnectionClosed(Amqp1Error error);
}
