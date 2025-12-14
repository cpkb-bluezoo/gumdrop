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

package org.bluezoo.gumdrop.smtp.handler;

/**
 * Operations available immediately after a client connection is established.
 * 
 * <p>This interface is provided to {@link ClientConnected#connected} and
 * allows the handler to either accept or reject the connection.
 * 
 * <p>Accepting the connection sends a 220 greeting to the client and
 * transitions to the hello state where HELO/EHLO commands are expected.
 * Rejecting the connection sends a 554 error and closes the connection.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientConnected#connected
 * @see HelloHandler
 */
public interface ConnectedState {

    /**
     * Accepts the connection and sends a greeting banner.
     * 
     * <p>Sends a 220 response to the client with the specified greeting
     * message and transitions to the hello state where HELO/EHLO commands
     * are expected.
     * 
     * <p>The greeting message typically includes the server hostname and
     * optionally indicates ESMTP support. For example:
     * <pre>
     * "mail.example.com ESMTP Service ready"
     * </pre>
     * 
     * @param greeting the greeting text to send (without the 220 code)
     * @param handler receives the client's HELO/EHLO command
     */
    void acceptConnection(String greeting, HelloHandler handler);

    /**
     * Rejects the connection with a default message.
     * 
     * <p>Sends a 554 response and closes the connection.
     * {@link ClientConnected#disconnected()} will be called.
     */
    void rejectConnection();

    /**
     * Rejects the connection with a custom message.
     * 
     * <p>Sends a 554 response with the specified message and closes
     * the connection. {@link ClientConnected#disconnected()} will be called.
     * 
     * @param message the rejection message (without the 554 code)
     */
    void rejectConnection(String message);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends a 421 response indicating the server is shutting down
     * and closes the connection.
     */
    void serverShuttingDown();

}

