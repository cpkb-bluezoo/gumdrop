/*
 * ConnectedState.java
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

package org.bluezoo.gumdrop.ftp.server;

/**
 * Operations available immediately after a control connection is established.
 *
 * <p>Accepting sends {@code 220} and enters the login phase. Rejecting sends
 * {@code 421} and closes the connection.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ConnectedState {

    /**
     * Accepts the connection and sends a {@code 220} greeting.
     *
     * @param greeting custom greeting text, or {@code null} for the default
     * @param handler receives USER / AUTH commands
     */
    void acceptConnection(String greeting, NotAuthenticatedHandler handler);

    /**
     * Accepts the connection when the client is already authenticated (e.g.
     * TLS client certificate).
     *
     * @param greeting greeting text for the {@code 230} response
     * @param handler receives post-login commands
     */
    void acceptLoggedIn(String greeting, AuthenticatedHandler handler);

    /**
     * Rejects the connection with {@code 421} using the default message.
     */
    void rejectConnection();

    /**
     * Rejects the connection with {@code 421} and a custom message.
     *
     * @param message the service-unavailable text
     */
    void rejectConnection(String message);

}
