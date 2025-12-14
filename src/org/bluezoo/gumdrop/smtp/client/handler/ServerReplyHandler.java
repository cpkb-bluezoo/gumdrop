/*
 * ServerReplyHandler.java
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

package org.bluezoo.gumdrop.smtp.client.handler;

/**
 * Base interface for all server reply handlers.
 * 
 * <p>This interface provides universal error handling for the 421 "service closing"
 * response, which can occur at any point during an SMTP session. When the server
 * sends a 421 response, the connection will be closed automatically after this
 * callback is invoked.
 * 
 * <p>All specific reply handler interfaces extend this base interface to inherit
 * the service closing handler.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting
 */
public interface ServerReplyHandler {

    /**
     * Called when the server sends a 421 "service closing" response.
     * 
     * <p>This can occur at any point during the SMTP session and indicates
     * that the server is closing the connection. The connection will be
     * closed automatically after this callback returns.
     * 
     * <p>Common reasons for 421 responses:
     * <ul>
     * <li>Server is shutting down for maintenance</li>
     * <li>Too many connections from this client</li>
     * <li>Connection timeout due to inactivity</li>
     * <li>Server resource exhaustion</li>
     * </ul>
     * 
     * @param message the server's closing message
     */
    void handleServiceClosing(String message);

}

