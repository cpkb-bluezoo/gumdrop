/*
 * DeleteState.java
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
 * Operations available when responding to a DELETE command.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthenticatedHandler#delete
 */
public interface DeleteState {

    /**
     * Mailbox deleted successfully.
     * 
     * @param handler continues receiving authenticated commands
     */
    void deleted(AuthenticatedHandler handler);

    /**
     * Mailbox does not exist.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void mailboxNotFound(String message, AuthenticatedHandler handler);

    /**
     * Cannot delete mailbox (has children, is INBOX, etc.).
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void cannotDelete(String message, AuthenticatedHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends an untagged BYE response indicating the server is shutting
     * down and closes the connection.
     */
    void serverShuttingDown();

}
