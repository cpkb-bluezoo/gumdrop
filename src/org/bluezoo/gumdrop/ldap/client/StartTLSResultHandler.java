/*
 * StartTLSResultHandler.java
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
 * Handler for STARTTLS extended operation results.
 * 
 * <p>This handler receives the result of a STARTTLS request, which
 * upgrades the connection to use TLS encryption.
 * 
 * <p>On success, the handler receives an {@link LDAPPostTLS} interface
 * and should proceed to bind (credentials are now protected).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LDAPConnected#startTLS
 * @see LDAPPostTLS
 */
public interface StartTLSResultHandler {

    /**
     * Called when TLS is successfully established.
     * 
     * <p>The connection is now encrypted. The handler should proceed
     * to bind to authenticate.
     * 
     * @param postTLS operations available after TLS upgrade
     */
    void handleTLSEstablished(LDAPPostTLS postTLS);

    /**
     * Called when STARTTLS fails.
     * 
     * <p>This may occur if:
     * <ul>
     * <li>The server does not support STARTTLS</li>
     * <li>TLS handshake fails</li>
     * <li>Certificate validation fails</li>
     * </ul>
     * 
     * <p>The handler can choose to continue without TLS (if acceptable)
     * or close the connection.
     * 
     * @param result the failure result
     * @param connection operations to continue or close
     */
    void handleStartTLSFailure(LDAPResult result, LDAPConnected connection);

}

