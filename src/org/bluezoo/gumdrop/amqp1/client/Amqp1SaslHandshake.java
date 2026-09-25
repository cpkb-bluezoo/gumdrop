/*
 * Amqp1SaslHandshake.java
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

import java.util.concurrent.ExecutorService;

import org.bluezoo.gumdrop.auth.SaslClientMechanism;

/**
 * State after the server announced its SASL mechanisms: choose one and
 * authenticate.
 *
 * <p>Each method may be called once. The chosen mechanism must be one the
 * server offered; otherwise the connection is failed with
 * {@link Amqp1ConnectionReady#onError}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1SaslHandshake {

    /**
     * Authenticates with SASL {@code PLAIN} (RFC 4616).
     *
     * <p>Only use this over TLS: {@code PLAIN} sends the password in the
     * clear.
     *
     * @param username the authentication identity
     * @param password the password
     * @param handler receives the outcome
     */
    void authenticate(String username, String password, Amqp1AuthHandler handler);

    /**
     * Authenticates with SASL {@code ANONYMOUS} (RFC 4505).
     *
     * @param trace optional trace information sent to the server, or
     *      {@code null}
     * @param handler receives the outcome
     */
    void authenticateAnonymous(String trace, Amqp1AuthHandler handler);

    /**
     * Authenticates with an arbitrary non-blocking SASL mechanism (for
     * example {@code EXTERNAL} after a TLS client-certificate handshake).
     *
     * <p>Not for {@code GSSAPI}, whose first challenge evaluation may
     * block on KDC contact; use
     * {@link #authenticate(SaslClientMechanism, Amqp1AuthHandler, ExecutorService)}.
     *
     * @param mechanism the mechanism driving the exchange
     * @param handler receives the outcome
     */
    void authenticate(SaslClientMechanism mechanism, Amqp1AuthHandler handler);

    /**
     * Authenticates with an arbitrary SASL mechanism, offloading each
     * challenge evaluation to {@code executor} and dispatching the result
     * back onto the connection's event loop.
     *
     * @param mechanism the mechanism driving the exchange
     * @param handler receives the outcome
     * @param executor worker executor for blocking challenge evaluation
     */
    void authenticate(SaslClientMechanism mechanism, Amqp1AuthHandler handler,
            ExecutorService executor);
}
