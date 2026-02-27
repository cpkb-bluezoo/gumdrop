/*
 * ServerReplyHandler.java
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

package org.bluezoo.gumdrop.pop3.client.handler;

/**
 * Base interface for all POP3 server reply handlers.
 *
 * <p>This interface provides universal error handling for unexpected
 * connection closure, which can occur at any point during a POP3 session.
 * When the server closes the connection unexpectedly, the callback is
 * invoked and the connection will be closed automatically.
 *
 * <p>All specific reply handler interfaces extend this base interface to
 * inherit the service closing handler.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting
 */
public interface ServerReplyHandler {

    /**
     * Called when the server closes the connection unexpectedly.
     *
     * <p>This can occur at any point during the POP3 session and indicates
     * that the server is closing the connection. The connection will be
     * closed automatically after this callback returns.
     *
     * @param message the server's closing message, or null if the
     *                connection was lost without a message
     */
    void handleServiceClosing(String message);

}
