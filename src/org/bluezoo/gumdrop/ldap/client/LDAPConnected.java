/*
 * LDAPConnected.java
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

/**
 * Operations available after LDAP connection is established.
 * 
 * <p>This interface is provided to the handler in
 * {@link LDAPConnectionReady#handleReady} after the TCP connection
 * (and TLS handshake for LDAPS) is complete.
 * 
 * <p>Unlike SMTP which has a server greeting, LDAP clients initiate
 * the conversation. At this stage, the handler should typically:
 * <ul>
 * <li>Bind with credentials using {@code bind()}</li>
 * <li>Bind anonymously using {@code bindAnonymous()}</li>
 * <li>Upgrade to TLS using {@code startTLS()} (for non-LDAPS connections)</li>
 * </ul>
 * 
 * <p>Most LDAP operations require a successful bind first. After binding,
 * the handler receives an {@link LDAPSession} interface for performing
 * directory operations.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LDAPConnectionReady#handleReady
 * @see LDAPSession
 */
public interface LDAPConnected {

    /**
     * Performs a simple bind (authentication) with DN and password.
     * 
     * <p>This is the standard authentication method for LDAP. After a
     * successful bind, the handler receives an {@link LDAPSession} for
     * performing directory operations.
     * 
     * @param dn the distinguished name to bind as (e.g., "cn=admin,dc=example,dc=com")
     * @param password the password for authentication
     * @param callback receives the bind result
     */
    void bind(String dn, String password, BindResultHandler callback);

    /**
     * Performs an anonymous bind.
     * 
     * <p>Anonymous binds use empty DN and password. Some servers allow
     * limited operations (typically read-only searches) for anonymous
     * connections.
     * 
     * @param callback receives the bind result
     */
    void bindAnonymous(BindResultHandler callback);

    /**
     * Initiates a STARTTLS upgrade to encrypt the connection.
     * 
     * <p>This should be called before binding to protect credentials.
     * The {@link LDAPClient} must have been configured with an
     * {@link javax.net.ssl.SSLContext} before connecting.
     * 
     * <p>After successful TLS upgrade, the handler receives an
     * {@link LDAPPostTLS} interface and should proceed to bind.
     * 
     * @param callback receives the STARTTLS result
     */
    void startTLS(StartTLSResultHandler callback);

    /**
     * Closes the connection without binding.
     * 
     * <p>Sends an unbind request and closes the connection. This is
     * appropriate when the handler decides not to proceed with the
     * connection (e.g., TLS not available when required).
     */
    void unbind();

}

