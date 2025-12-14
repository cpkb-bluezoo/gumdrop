/*
 * BindResultHandler.java
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
 * Handler for bind operation results.
 * 
 * <p>This handler receives the result of a bind operation, which
 * authenticates the client to the LDAP server.
 * 
 * <p>On success, the handler receives an {@link LDAPSession} interface
 * for performing directory operations. On failure, the handler receives
 * the error result and an {@link LDAPConnected} interface to retry
 * with different credentials or close the connection.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LDAPConnected#bind
 * @see LDAPSession
 */
public interface BindResultHandler {

    /**
     * Called when bind succeeds.
     * 
     * <p>The connection is now authenticated and can perform directory
     * operations using the provided session interface.
     * 
     * @param session operations available in the authenticated session
     */
    void handleBindSuccess(LDAPSession session);

    /**
     * Called when bind fails.
     * 
     * <p>Common failure reasons include:
     * <ul>
     * <li>{@code INVALID_CREDENTIALS} - wrong password</li>
     * <li>{@code NO_SUCH_OBJECT} - DN does not exist</li>
     * <li>{@code INAPPROPRIATE_AUTHENTICATION} - method not allowed</li>
     * </ul>
     * 
     * <p>The handler can retry with different credentials using the
     * provided connection interface, or close the connection.
     * 
     * @param result the failure result with error code and message
     * @param connection operations to retry or close
     */
    void handleBindFailure(LDAPResult result, LDAPConnected connection);

}

