/*
 * Amqp1AuthHandler.java
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

/**
 * Receives the outcome of the SASL exchange.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1AuthHandler {

    /**
     * Authentication succeeded. The AMQP protocol header has been sent;
     * the connection is ready to be opened.
     *
     * @param opener used to send the {@code open} performative
     */
    void handleAuthenticated(Amqp1ConnectionOpener opener);

    /**
     * The server rejected the authentication. The connection is closed
     * after this callback.
     *
     * @param code the SASL outcome code: {@code 1} authentication
     *      failed, {@code 2} system error, {@code 3} permanent system
     *      error, {@code 4} temporary system error
     * @param additionalData mechanism-specific data from the server, or
     *      {@code null}
     */
    void handleAuthenticationFailed(int code, byte[] additionalData);
}
