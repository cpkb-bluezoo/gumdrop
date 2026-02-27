/*
 * ClientAuthorizationState.java
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
 * Operations available in the POP3 AUTHORIZATION state.
 *
 * <p>This interface is provided to the handler after receiving the server
 * greeting and allows the handler to authenticate using any of the
 * supported mechanisms, upgrade to TLS, or query capabilities.
 *
 * <p>The POP3 AUTHORIZATION state supports the following operations:
 * <ul>
 * <li>{@code capa()} - Query server capabilities (RFC 2449)</li>
 * <li>{@code user()} - Begin USER/PASS authentication (RFC 1939)</li>
 * <li>{@code apop()} - APOP authentication (RFC 1939)</li>
 * <li>{@code auth()} - SASL authentication (RFC 5034)</li>
 * <li>{@code stls()} - Upgrade to TLS (RFC 2595)</li>
 * <li>{@code quit()} - Close the connection</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting#handleGreeting
 */
public interface ClientAuthorizationState {

    /**
     * Sends a CAPA command to query server capabilities.
     *
     * <p>The CAPA response reveals supported extensions such as STLS,
     * SASL mechanisms, TOP, UIDL, and PIPELINING.
     *
     * @param callback receives the server's capability response
     */
    void capa(ServerCapaReplyHandler callback);

    /**
     * Sends a USER command to begin USER/PASS authentication.
     *
     * <p>If the server accepts the username, the handler receives a
     * {@link ClientPasswordState} to continue with PASS.
     *
     * @param username the username to authenticate
     * @param callback receives the server's response
     */
    void user(String username, ServerUserReplyHandler callback);

    /**
     * Sends an APOP command for digest-based authentication.
     *
     * <p>APOP requires the server to have provided a timestamp in its
     * greeting. The digest should be the MD5 hash of the timestamp
     * concatenated with the shared secret.
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
     * <p>The mechanism should be one of those advertised in the server's
     * CAPA response. The initial response may be null for mechanisms
     * that don't support it.
     *
     * @param mechanism the SASL mechanism name (e.g., "PLAIN", "LOGIN")
     * @param initialResponse initial response bytes, or null
     * @param callback receives the server's response
     */
    void auth(String mechanism, byte[] initialResponse,
              ServerAuthReplyHandler callback);

    /**
     * Sends a STLS command to upgrade the connection to TLS.
     *
     * <p>This should only be called if the server advertised STLS in its
     * CAPA response and an SSLContext was configured on the client.
     * After successful TLS upgrade, the handler should re-issue CAPA
     * to learn the server's post-TLS capabilities.
     *
     * @param callback receives the server's response
     */
    void stls(ServerStlsReplyHandler callback);

    /**
     * Closes the connection gracefully.
     *
     * <p>Sends a QUIT command and closes the connection.
     */
    void quit();

}
