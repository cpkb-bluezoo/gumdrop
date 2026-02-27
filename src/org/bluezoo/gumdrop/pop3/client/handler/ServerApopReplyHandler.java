/*
 * ServerApopReplyHandler.java
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
 * Handler for APOP command response.
 *
 * <p>On success, the handler enters the TRANSACTION state. On failure,
 * the handler returns to the AUTHORIZATION state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthorizationState#apop
 * @see ClientTransactionState
 */
public interface ServerApopReplyHandler extends ServerReplyHandler {

    /**
     * Called when APOP authentication succeeds (+OK).
     *
     * @param transaction operations available in the TRANSACTION state
     */
    void handleAuthenticated(ClientTransactionState transaction);

    /**
     * Called when APOP authentication fails (-ERR).
     *
     * @param auth operations to retry in the AUTHORIZATION state
     * @param message the server's error message
     */
    void handleAuthFailed(ClientAuthorizationState auth, String message);

}
