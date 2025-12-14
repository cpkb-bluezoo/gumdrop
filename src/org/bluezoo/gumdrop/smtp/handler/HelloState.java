/*
 * HelloState.java
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
 * Operations available when responding to HELO/EHLO.
 * 
 * <p>This interface is provided to {@link HelloHandler#hello} and allows
 * the handler to accept or reject the client's greeting.
 * 
 * <p>Accepting the greeting sends a 250 response (with extensions for EHLO)
 * and transitions to the mail-from ready state. Rejecting sends an error
 * response; the client may retry or disconnect.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HelloHandler#hello
 * @see MailFromHandler
 */
public interface HelloState {

    /**
     * Accepts the greeting and transitions to mail-from state.
     * 
     * <p>For EHLO, sends a 250 multi-line response advertising available
     * extensions (SIZE, STARTTLS, AUTH, PIPELINING, etc.). For HELO, sends
     * a simple 250 response.
     * 
     * @param handler receives subsequent MAIL FROM commands
     */
    void acceptHello(MailFromHandler handler);

    /**
     * Temporarily rejects the greeting (allow retry).
     * 
     * <p>Sends a 421 response. The client may retry the greeting.
     * 
     * @param message the rejection message
     * @param handler receives the retry attempt
     */
    void rejectHelloTemporary(String message, HelloHandler handler);

    /**
     * Permanently rejects the greeting.
     * 
     * <p>Sends a 550 response. The client may retry with a different hostname.
     * 
     * @param message the rejection message
     * @param handler receives the retry attempt
     */
    void rejectHello(String message, HelloHandler handler);

    /**
     * Permanently rejects the greeting and closes the connection.
     * 
     * <p>Sends a 554 response and closes the connection.
     * {@link ClientConnected#disconnected()} will be called.
     * 
     * @param message the rejection message
     */
    void rejectHelloAndClose(String message);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends a 421 response indicating the server is shutting down
     * and closes the connection.
     */
    void serverShuttingDown();

}

