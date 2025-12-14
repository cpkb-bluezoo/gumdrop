/*
 * AuthenticateState.java
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

package org.bluezoo.gumdrop.pop3.handler;

import org.bluezoo.gumdrop.mailbox.Mailbox;

/**
 * Operations available when responding to an authentication event.
 * 
 * <p>This interface is provided to {@link AuthorizationHandler#authenticate}
 * after the Realm has verified credentials. The handler makes a policy
 * decision on whether to allow this authenticated principal access.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthorizationHandler#authenticate
 */
public interface AuthenticateState {

    /**
     * Accepts the authenticated principal and opens the mailbox.
     * 
     * <p>Transitions to TRANSACTION state. The handler will receive
     * subsequent commands via the TransactionHandler.
     * 
     * @param mailbox the user's opened mailbox
     * @param handler receives transaction state commands
     */
    void accept(Mailbox mailbox, TransactionHandler handler);

    /**
     * Rejects the authenticated principal.
     * 
     * <p>Even though the Realm authenticated the user, the handler
     * is vetoing access (e.g., account disabled, access policy).
     * 
     * @param message the rejection message
     * @param handler continues receiving authorization commands
     */
    void reject(String message, AuthorizationHandler handler);

    /**
     * Rejects and closes the connection.
     * 
     * <p>Use for severe policy violations where the client should
     * not be allowed to retry.
     * 
     * @param message the rejection message
     */
    void rejectAndClose(String message);

    /**
     * Server is shutting down.
     */
    void serverShuttingDown();

}

