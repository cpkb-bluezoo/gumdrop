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

package org.bluezoo.gumdrop.pop3.handler;

/**
 * Operations available immediately after a client connection is established.
 * 
 * <p>This interface is provided to {@link ClientConnected#connected} and
 * allows the handler to either accept or reject the connection.
 * 
 * <p>Accepting the connection sends a +OK greeting to the client and
 * transitions to the AUTHORIZATION state where USER/PASS, APOP, or AUTH
 * commands are expected (RFC 1939 Section 3).
 * Rejecting the connection sends a -ERR error and closes the connection.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientConnected#connected
 * @see AuthorizationHandler
 */
public interface ConnectedState {

    /**
     * Accepts the connection and sends a greeting banner.
     * 
     * <p>Sends a +OK response to the client with the specified greeting
     * message and transitions to the AUTHORIZATION state where authentication
     * commands are expected.
     * 
     * <p>The greeting message typically includes the server name. For example:
     * <pre>
     * "POP3 server ready"
     * </pre>
     * 
     * @param greeting the greeting text to send (without the +OK prefix)
     * @param handler receives authentication events after Realm verification
     */
    void acceptConnection(String greeting, AuthorizationHandler handler);

    /**
     * Accepts the connection with APOP capability and sends a greeting banner.
     * 
     * <p>Sends a +OK response with an APOP timestamp included in the greeting,
     * allowing clients to authenticate using APOP (RFC 1939 Section 7).
     * 
     * <p>The timestamp should be in the format: {@code <process-id.clock@hostname>}
     * 
     * @param greeting the greeting text (without the +OK prefix or timestamp)
     * @param timestamp the APOP timestamp (will be appended to the greeting)
     * @param handler receives authentication events after Realm verification
     */
    void acceptConnectionWithApop(String greeting, String timestamp, AuthorizationHandler handler);

    /**
     * Rejects the connection with a default message.
     * 
     * <p>Sends a -ERR response and closes the connection.
     * {@link ClientConnected#disconnected()} will be called.
     */
    void rejectConnection();

    /**
     * Rejects the connection with a custom message.
     * 
     * <p>Sends a -ERR response with the specified message and closes
     * the connection. {@link ClientConnected#disconnected()} will be called.
     * 
     * @param message the rejection message (without the -ERR prefix)
     */
    void rejectConnection(String message);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends a -ERR response indicating the server is shutting down
     * and closes the connection.
     */
    void serverShuttingDown();

}

