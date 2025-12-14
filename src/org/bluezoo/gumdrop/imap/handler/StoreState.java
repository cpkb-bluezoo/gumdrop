/*
 * StoreState.java
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

import org.bluezoo.gumdrop.mailbox.Flag;

import java.util.Set;

/**
 * Operations available when responding to STORE commands.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SelectedHandler#store
 * @see SelectedHandler#uidStore
 */
public interface StoreState {

    /**
     * Sends a FETCH response with updated flags.
     * 
     * <p>Unless .SILENT was used, call this for each modified message.
     * 
     * @param sequenceNumber the message sequence number
     * @param flags the new flags
     */
    void flagsUpdated(int sequenceNumber, Set<Flag> flags);

    /**
     * Store completed successfully.
     * 
     * @param handler continues receiving selected commands
     */
    void storeComplete(SelectedHandler handler);

    /**
     * Store failed (e.g., read-only mailbox).
     * 
     * @param message the error message
     * @param handler continues receiving selected commands
     */
    void storeFailed(String message, SelectedHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends an untagged BYE response indicating the server is shutting
     * down and closes the connection.
     */
    void serverShuttingDown();

}
