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

package org.bluezoo.gumdrop.ftp.client.handler;

/**
 * Base interface for all server reply handlers.
 * RFC 959 §4.2 (421 service not available, closing control connection).
 *
 * <p>This interface provides universal error handling for the 421 response,
 * which can occur at any point during an FTP session. When the server sends
 * a 421 response, the connection will be closed automatically after this
 * callback is invoked.
 *
 * <p>All specific reply handler interfaces extend this base interface to
 * inherit the service-closing handler.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a>
 */
public interface ServerReplyHandler {

    /**
     * Called when the server sends a 421 "service not available, closing
     * control connection" response.
     *
     * <p>This can occur at any point during the FTP session. The connection
     * will be closed automatically after this callback returns.
     *
     * @param message the server's closing message
     */
    void handleServiceClosing(String message);

}
