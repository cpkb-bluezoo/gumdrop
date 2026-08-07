/*
 * ServerAuthTlsReplyHandler.java
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
 * Handler for AUTH TLS command response. RFC 4217 §4.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientLoginState#authTls
 */
public interface ServerAuthTlsReplyHandler extends ServerReplyHandler {

    /**
     * Called once the TLS handshake on the control connection has
     * completed (following a 234 response).
     *
     * @param login operations available to continue the (now secure)
     *      session
     */
    void handleTlsEstablished(ClientLoginState login);

    /**
     * Called when the server does not support AUTH TLS (502, 500, etc).
     *
     * @param login operations to continue the plaintext session
     */
    void handleTlsUnavailable(ClientLoginState login);

}
