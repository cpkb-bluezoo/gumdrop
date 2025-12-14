/*
 * AuthenticatedStatusState.java
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

package org.bluezoo.gumdrop.imap.handler;

/**
 * Operations available when responding to a STATUS command in AUTHENTICATED state.
 * 
 * <p>This interface is provided to {@link AuthenticatedHandler#status} and
 * allows the handler to proceed with the status query or deny it.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthenticatedHandler#status
 */
public interface AuthenticatedStatusState {

    /**
     * Proceeds with the status query.
     * 
     * <p>The server will query the mailbox and send a STATUS response.
     * 
     * @param handler continues receiving authenticated commands
     */
    void proceed(AuthenticatedHandler handler);

    /**
     * Denies the status query.
     * 
     * <p>Sends a NO response to the client.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void deny(String message, AuthenticatedHandler handler);

    /**
     * The mailbox does not exist.
     * 
     * @param handler continues receiving authenticated commands
     */
    void mailboxNotFound(AuthenticatedHandler handler);

    /**
     * Server is shutting down.
     */
    void serverShuttingDown();

}

