/*
 * LDAPPostTLS.java
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
 * Operations available after STARTTLS upgrade completes.
 * 
 * <p>This interface is provided to the handler in
 * {@link StartTLSResultHandler#handleTLSEstablished} after a successful
 * STARTTLS extended operation and TLS handshake.
 * 
 * <p>After TLS is established, the handler should bind to authenticate.
 * Directory operations are not available until binding succeeds.
 * 
 * <p>Note: STARTTLS cannot be issued again after the connection is
 * already secured, so that method is not available in this interface.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see StartTLSResultHandler#handleTLSEstablished
 * @see LDAPSession
 */
public interface LDAPPostTLS {

    /**
     * Performs a simple bind (authentication) with DN and password.
     * 
     * <p>Credentials are now protected by TLS. After a successful bind,
     * the handler receives an {@link LDAPSession} for performing
     * directory operations.
     * 
     * @param dn the distinguished name to bind as
     * @param password the password for authentication
     * @param callback receives the bind result
     */
    void bind(String dn, String password, BindResultHandler callback);

    /**
     * Performs an anonymous bind.
     * 
     * <p>Anonymous binds use empty DN and password. Some servers allow
     * limited operations for anonymous connections.
     * 
     * @param callback receives the bind result
     */
    void bindAnonymous(BindResultHandler callback);

    /**
     * Closes the connection without binding.
     * 
     * <p>Sends an unbind request and closes the connection.
     */
    void unbind();

}

