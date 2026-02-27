/*
 * ClientPostStls.java
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
 * Operations available after a successful STLS upgrade.
 *
 * <p>This interface is provided to the handler after STLS succeeds and
 * the TLS handshake completes. Per RFC 2449, the client SHOULD re-issue
 * CAPA after TLS upgrade to learn the server's post-TLS capabilities
 * (e.g., additional SASL mechanisms may become available).
 *
 * <p>Authentication operations are available directly for cases where
 * re-issuing CAPA is not required.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerStlsReplyHandler#handleTlsEstablished
 */
public interface ClientPostStls {

    /**
     * Re-issues CAPA after TLS upgrade.
     *
     * <p>This is the recommended first action after STLS. The server
     * may advertise different capabilities over the encrypted connection.
     *
     * @param callback receives the server's capability response
     */
    void capa(ServerCapaReplyHandler callback);

    /**
     * Sends a USER command to begin USER/PASS authentication.
     *
     * @param username the username to authenticate
     * @param callback receives the server's response
     */
    void user(String username, ServerUserReplyHandler callback);

    /**
     * Sends an APOP command for digest-based authentication.
     *
     * @param username the username to authenticate
     * @param digest the MD5 hex digest of timestamp + password
     * @param callback receives the server's response
     */
    void apop(String username, String digest,
              ServerApopReplyHandler callback);

    /**
     * Initiates SASL authentication.
     *
     * @param mechanism the SASL mechanism name
     * @param initialResponse initial response bytes, or null
     * @param callback receives the server's response
     */
    void auth(String mechanism, byte[] initialResponse,
              ServerAuthReplyHandler callback);

    /**
     * Closes the connection.
     */
    void quit();

}
