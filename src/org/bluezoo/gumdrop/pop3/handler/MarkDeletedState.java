/*
 * MarkDeletedState.java
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
 * Operations available when responding to a mark-deleted request.
 * 
 * <p>This interface is provided to {@link TransactionHandler#markDeleted} and
 * allows the handler to confirm or reject the deletion request.
 * 
 * <p>Messages marked for deletion are not actually removed until the
 * session ends normally with QUIT (entering the UPDATE state).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TransactionHandler#markDeleted
 */
public interface MarkDeletedState {

    /**
     * Confirms the message is marked for deletion.
     * 
     * <p>Sends a +OK response confirming the deletion.
     * 
     * @param handler continues receiving transaction commands
     */
    void markedDeleted(TransactionHandler handler);

    /**
     * Confirms the deletion with a custom message.
     * 
     * @param message the confirmation message
     * @param handler continues receiving transaction commands
     */
    void markedDeleted(String message, TransactionHandler handler);

    /**
     * The requested message does not exist.
     * 
     * @param handler continues receiving transaction commands
     */
    void noSuchMessage(TransactionHandler handler);

    /**
     * The message has already been deleted.
     * 
     * @param handler continues receiving transaction commands
     */
    void alreadyDeleted(TransactionHandler handler);

    /**
     * Reports an error marking the message.
     * 
     * @param message the error message
     * @param handler continues receiving transaction commands
     */
    void error(String message, TransactionHandler handler);

}

