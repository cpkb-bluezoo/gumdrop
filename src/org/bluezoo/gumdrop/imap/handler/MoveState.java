/*
 * MoveState.java
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
 * Operations available when responding to MOVE commands.
 * 
 * <p>MOVE is similar to COPY followed by STORE \Deleted and EXPUNGE,
 * but is atomic (RFC 6851).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SelectedHandler#move
 * @see SelectedHandler#uidMove
 */
public interface MoveState {

    /**
     * Sends an EXPUNGE notification for a moved message.
     * 
     * <p>Call this for each message that was moved.
     * 
     * @param sequenceNumber the sequence number of the moved message
     */
    void messageExpunged(int sequenceNumber);

    /**
     * Move completed successfully.
     * 
     * @param handler continues receiving selected commands
     */
    void moved(SelectedHandler handler);

    /**
     * Move completed with COPYUID response code.
     * 
     * @param uidValidity the target mailbox UID validity
     * @param sourceUids the source message UIDs
     * @param destUids the destination message UIDs
     * @param handler continues receiving selected commands
     */
    void movedWithUid(long uidValidity, String sourceUids, String destUids, 
                      SelectedHandler handler);

    /**
     * Target mailbox does not exist.
     * 
     * @param message the error message
     * @param handler continues receiving selected commands
     */
    void mailboxNotFound(String message, SelectedHandler handler);

    /**
     * Move failed (quota exceeded, permission denied, etc.).
     * 
     * @param message the error message
     * @param handler continues receiving selected commands
     */
    void moveFailed(String message, SelectedHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends an untagged BYE response indicating the server is shutting
     * down and closes the connection.
     */
    void serverShuttingDown();

}
