/*
 * LDAPConnectionReady.java
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

package org.bluezoo.gumdrop.ldap.client;

import org.bluezoo.gumdrop.ClientHandler;

/**
 * Handler interface for receiving the initial LDAP connection ready event.
 * 
 * <p>This is the entry point for LDAP client handlers. When connecting to an
 * LDAP server, the handler passed to {@code LDAPClient.connect()} must implement
 * this interface to receive notification that the connection is ready.
 * 
 * <p>Unlike SMTP which has a server greeting, LDAP clients initiate the
 * conversation. The handler receives an {@link LDAPConnected} interface
 * immediately upon connection (and TLS handshake for LDAPS).
 * 
 * <p><strong>Example usage:</strong>
 * <pre>{@code
 * public class MyLDAPHandler implements LDAPConnectionReady {
 *     
 *     public void handleReady(LDAPConnected connection) {
 *         // Upgrade to TLS first (for ldap:// connections)
 *         connection.startTLS(new StartTLSResultHandler() {
 *             public void handleTLSEstablished(LDAPPostTLS postTLS) {
 *                 postTLS.bind("cn=admin,dc=example,dc=com", "secret", 
 *                              new MyBindHandler());
 *             }
 *             // ...
 *         });
 *     }
 *     
 *     // ... ClientHandler methods ...
 * }
 * }</pre>
 * 
 * <p><strong>Simple bind example:</strong>
 * <pre>{@code
 * public void handleReady(LDAPConnected connection) {
 *     connection.bind("cn=admin,dc=example,dc=com", "secret",
 *         new BindResultHandler() {
 *             public void handleBindSuccess(LDAPSession session) {
 *                 // Perform searches, modifications, etc.
 *                 session.search(request, new MySearchHandler());
 *             }
 *             public void handleBindFailure(LDAPResult result, LDAPConnected conn) {
 *                 log.error("Bind failed: {}", result.getDiagnosticMessage());
 *                 conn.unbind();
 *             }
 *         });
 * }
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LDAPConnected
 * @see ClientHandler
 */
public interface LDAPConnectionReady extends ClientHandler {

    /**
     * Called when the LDAP connection is ready for operations.
     * 
     * <p>This is called immediately after TCP connection (and TLS handshake
     * for LDAPS) is complete. The handler should typically:
     * <ul>
     * <li>For plaintext connections: upgrade to TLS with {@code startTLS()},
     *     then bind</li>
     * <li>For LDAPS connections: bind directly</li>
     * </ul>
     * 
     * @param connection operations available to begin the session
     */
    void handleReady(LDAPConnected connection);

}

