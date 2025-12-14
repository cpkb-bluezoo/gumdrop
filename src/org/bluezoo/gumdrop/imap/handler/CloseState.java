/*
 * CloseState.java
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
 * Operations available when responding to CLOSE or UNSELECT commands.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SelectedHandler#close
 * @see SelectedHandler#unselect
 */
public interface CloseState {

    /**
     * Mailbox closed/unselected successfully.
     * 
     * <p>Returns to AUTHENTICATED state.
     * 
     * @param handler continues receiving authenticated commands
     */
    void closed(AuthenticatedHandler handler);

    /**
     * Close failed.
     * 
     * <p>Mailbox is still selected.
     * 
     * @param message the error message
     * @param handler continues receiving selected commands
     */
    void closeFailed(String message, SelectedHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends an untagged BYE response indicating the server is shutting
     * down and closes the connection.
     */
    void serverShuttingDown();

}
