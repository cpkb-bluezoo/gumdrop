/*
 * UpdateState.java
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

/**
 * Operations available when responding to QUIT in TRANSACTION state.
 * 
 * <p>This interface is provided to {@link TransactionHandler#quit} and
 * allows the handler to commit mailbox changes (delete marked messages)
 * and close the connection (RFC 1939 Section 6).
 * 
 * <p>This transitions the connection to the UPDATE state, where the
 * mailbox is closed and changes are committed.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TransactionHandler#quit
 */
public interface UpdateState {

    /**
     * Commits deletions and closes the connection successfully.
     * 
     * <p>Sends a +OK response indicating the session ended normally
     * and all marked messages were deleted.
     * {@link ClientConnected#disconnected()} will be called.
     */
    void commitAndClose();

    /**
     * Commits deletions and closes with a custom message.
     * 
     * @param message the farewell message
     */
    void commitAndClose(String message);

    /**
     * Some messages could not be deleted.
     * 
     * <p>Sends a -ERR response indicating some messages were not deleted.
     * The connection is still closed.
     * 
     * @param message the error message describing the failure
     */
    void partialCommit(String message);

    /**
     * The update failed entirely.
     * 
     * <p>Sends a -ERR response indicating the update failed.
     * The connection is still closed.
     * 
     * @param message the error message
     */
    void updateFailed(String message);

}

