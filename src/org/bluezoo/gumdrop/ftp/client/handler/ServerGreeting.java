/*
 * ServerGreeting.java
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

import org.bluezoo.gumdrop.ClientHandler;

/**
 * Handler interface for receiving the initial FTP server greeting.
 * RFC 959 §4.2 (220 service ready / 421 service not available).
 *
 * <p>This is the entry point for FTP client handlers. When connecting to an
 * FTP server, the handler passed to {@code FTPClient.connect()} must
 * implement this interface to receive the server's initial greeting and
 * begin the session with USER.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientLoginState
 * @see ClientHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a>
 */
public interface ServerGreeting extends ClientHandler {

    /**
     * Called when the server sends a successful greeting (220).
     *
     * <p>The handler should respond by issuing USER to begin
     * authentication.
     *
     * @param login operations available to begin the session
     * @param message the greeting text
     */
    void handleGreeting(ClientLoginState login, String message);

    /**
     * Called when the server is not accepting connections (421).
     *
     * <p>The connection will be closed after this callback returns.
     *
     * @param message the server's rejection message
     */
    void handleServiceUnavailable(String message);

}
