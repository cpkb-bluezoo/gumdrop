/*
 * ConnectedState.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.imap.handler;

/**
 * Operations available immediately after a client connection is established.
 * 
 * <p>This interface is provided to {@link ClientConnected#connected} and
 * allows the handler to either accept or reject the connection.
 * 
 * <p>Accepting the connection sends an untagged OK greeting to the client
 * and transitions to the NOT_AUTHENTICATED state (RFC 9051 Section 3.1).
 * Rejecting the connection sends a BYE response and closes the connection.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientConnected#connected
 * @see NotAuthenticatedHandler
 */
public interface ConnectedState {

    /**
     * Accepts the connection and sends a greeting banner.
     * 
     * <p>Sends an untagged OK response to the client with the specified
     * greeting message and transitions to the NOT_AUTHENTICATED state
     * where LOGIN/AUTHENTICATE commands are expected.
     * 
     * <p>The greeting typically includes server information. For example:
     * <pre>
     * "IMAP4rev2 server ready"
     * </pre>
     * 
     * @param greeting the greeting text (appears after "* OK ")
     * @param handler receives authentication commands
     */
    void acceptConnection(String greeting, NotAuthenticatedHandler handler);

    /**
     * Accepts the connection with PREAUTH status.
     * 
     * <p>Sends an untagged PREAUTH response indicating the client is
     * already authenticated (e.g., via TLS client certificate).
     * Transitions directly to the AUTHENTICATED state.
     * 
     * @param greeting the greeting text
     * @param handler receives authenticated state commands
     */
    void acceptPreauth(String greeting, AuthenticatedHandler handler);

    /**
     * Rejects the connection with a default message.
     * 
     * <p>Sends an untagged BYE response and closes the connection.
     * {@link ClientConnected#disconnected()} will be called.
     */
    void rejectConnection();

    /**
     * Rejects the connection with a custom message.
     * 
     * <p>Sends an untagged BYE response with the specified message and
     * closes the connection. {@link ClientConnected#disconnected()} will be called.
     * 
     * @param message the rejection message
     */
    void rejectConnection(String message);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends an untagged BYE response indicating the server is shutting
     * down and closes the connection.
     */
    void serverShuttingDown();

}

