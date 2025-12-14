/*
 * AppendState.java
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

import org.bluezoo.gumdrop.mailbox.Mailbox;

/**
 * Operations available when responding to an APPEND command.
 * 
 * <p>APPEND adds a message to a mailbox. The handler first validates
 * the target mailbox, then receives the message literal.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthenticatedHandler#append
 * @see AppendDataHandler
 */
public interface AppendState {

    /**
     * Ready to receive message data for the given mailbox.
     * 
     * <p>Convenience method that accepts the literal and associates
     * it with the target mailbox.
     * 
     * @param mailbox the target mailbox
     * @param handler receives the message data
     */
    void readyForData(Mailbox mailbox, AppendDataHandler handler);

    /**
     * Mailbox does not exist, client should try CREATE first.
     * 
     * <p>Sends a NO [TRYCREATE] response.
     * 
     * @param handler continues receiving authenticated commands
     */
    void tryCreate(AuthenticatedHandler handler);

    /**
     * Append failed.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void appendFailed(String message, AuthenticatedHandler handler);

    /**
     * Accepts the append and requests the message literal.
     * 
     * <p>Sends a continuation response (+) and waits for the
     * message data.
     * 
     * @param literalSize the expected literal size
     * @param handler receives the message data
     */
    void acceptLiteral(int literalSize, AppendDataHandler handler);

    /**
     * Mailbox does not exist.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void mailboxNotFound(String message, AuthenticatedHandler handler);

    /**
     * Cannot append to this mailbox.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void cannotAppend(String message, AuthenticatedHandler handler);

    /**
     * Message is too large.
     * 
     * @param maxSize the maximum allowed size
     * @param handler continues receiving authenticated commands
     */
    void messageTooLarge(long maxSize, AuthenticatedHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends an untagged BYE response indicating the server is shutting
     * down and closes the connection.
     */
    void serverShuttingDown();

}

