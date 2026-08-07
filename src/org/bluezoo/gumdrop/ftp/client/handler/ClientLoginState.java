/*
 * ClientLoginState.java
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
 * Operations available after receiving the server greeting, or after a
 * failed login attempt. RFC 959 §4.1.1 (USER); RFC 4217 (AUTH TLS).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting#handleGreeting
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4217">RFC 4217</a> (AUTH TLS)
 */
public interface ClientLoginState {

    /**
     * Sends a USER command to begin authentication.
     *
     * @param username the username to authenticate
     * @param callback receives the server's response
     */
    void user(String username, ServerUserReplyHandler callback);

    /**
     * Sends an AUTH TLS command to upgrade the control connection to TLS
     * before authenticating (RFC 4217 §4).
     *
     * @param callback receives the server's response
     */
    void authTls(ServerAuthTlsReplyHandler callback);

    /**
     * Closes the connection without authenticating.
     *
     * <p>Sends a QUIT command and closes the connection.
     */
    void quit();

}
