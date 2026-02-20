/*
 * TransactionHandler.java
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
 * Handler for POP3 TRANSACTION state commands.
 * 
 * <p>This handler receives commands during the TRANSACTION state (RFC 1939
 * Section 5), which is active after successful authentication. The client
 * can retrieve, delete, and manage messages in the mailbox.
 * 
 * <p>All methods receive the user's {@link Mailbox} established at login,
 * allowing the handler to access message data for each operation.
 * 
 * <p>Available operations:
 * <ul>
 *   <li>{@link #mailboxStatus} - get mailbox statistics (STAT)</li>
 *   <li>{@link #list} - list message sizes (LIST)</li>
 *   <li>{@link #retrieveMessage} - retrieve a message (RETR)</li>
 *   <li>{@link #markDeleted} - mark a message for deletion (DELE)</li>
 *   <li>{@link #reset} - undelete all messages (RSET)</li>
 *   <li>{@link #top} - retrieve message headers and N lines of body (TOP)</li>
 *   <li>{@link #uidl} - list unique identifiers (UIDL)</li>
 *   <li>{@link #quit} - commit deletions and close (QUIT)</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthenticateState#accept
 */
public interface TransactionHandler {

    /**
     * Called when the client requests mailbox status (STAT command).
     * 
     * <p>Returns the number of messages and total size of the mailbox.
     * 
     * @param state operations for responding with statistics
     * @param mailbox the user's mailbox
     */
    void mailboxStatus(MailboxStatusState state, Mailbox mailbox);

    /**
     * Called when the client requests message listing (LIST command).
     * 
     * <p>Returns message numbers and their sizes. If a message number
     * is provided, returns information for that message only; otherwise
     * returns information for all messages.
     * 
     * @param state operations for responding with the list
     * @param mailbox the user's mailbox
     * @param messageNumber the specific message number, or 0 for all messages
     */
    void list(ListState state, Mailbox mailbox, int messageNumber);

    /**
     * Called when the client requests message content (RETR command).
     * 
     * <p>Retrieves the full content of the specified message.
     * 
     * @param state operations for sending the message content
     * @param mailbox the user's mailbox
     * @param messageNumber the message number to retrieve
     */
    void retrieveMessage(RetrieveState state, Mailbox mailbox, int messageNumber);

    /**
     * Called when the client marks a message for deletion (DELE command).
     * 
     * <p>Marks the specified message for deletion. The message is
     * not actually deleted until the session ends normally with QUIT.
     * 
     * @param state operations for confirming the deletion
     * @param mailbox the user's mailbox
     * @param messageNumber the message number to delete
     */
    void markDeleted(MarkDeletedState state, Mailbox mailbox, int messageNumber);

    /**
     * Called when the client resets deletion marks (RSET command).
     * 
     * <p>Undeletes all messages marked for deletion in this session.
     * 
     * @param state operations for confirming the reset
     * @param mailbox the user's mailbox
     */
    void reset(ResetState state, Mailbox mailbox);

    /**
     * Called when the client requests message headers (TOP command).
     * 
     * <p>TOP retrieves the message headers and the first N lines of the
     * message body (RFC 1939 Section 7).
     * 
     * @param state operations for sending the content
     * @param mailbox the user's mailbox
     * @param messageNumber the message number
     * @param lines the number of body lines to retrieve
     */
    void top(TopState state, Mailbox mailbox, int messageNumber, int lines);

    /**
     * Called when the client requests unique identifiers (UIDL command).
     * 
     * <p>Returns unique identifiers for messages. If a message number
     * is provided, returns the UID for that message only; otherwise returns
     * UIDs for all messages.
     * 
     * @param state operations for responding with UIDs
     * @param mailbox the user's mailbox
     * @param messageNumber the specific message number, or 0 for all messages
     */
    void uidl(UidlState state, Mailbox mailbox, int messageNumber);

    // Note: CAPA and NOOP are handled automatically by the POP3Connection
    // since they have only one possible response and don't require handler
    // policy decisions.

    /**
     * Called when the client ends the session (QUIT command).
     * 
     * <p>QUIT in TRANSACTION state transitions to UPDATE state, committing
     * all deletions before closing the connection.
     * 
     * @param state operations for closing with update
     * @param mailbox the user's mailbox (to commit deletions)
     */
    void quit(UpdateState state, Mailbox mailbox);

}
