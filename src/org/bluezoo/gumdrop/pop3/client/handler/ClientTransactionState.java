/*
 * ClientTransactionState.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.pop3.client.handler;

/**
 * Operations available in the POP3 TRANSACTION state.
 *
 * <p>This interface is provided to the handler after successful
 * authentication (via USER/PASS, APOP, or SASL AUTH) and represents
 * the state where the client can access the mailbox.
 *
 * <p>Available operations correspond to POP3 TRANSACTION-state commands:
 * <ul>
 * <li>{@code stat()} - Get mailbox status (message count and size)</li>
 * <li>{@code list()} - List messages with sizes</li>
 * <li>{@code retr()} - Retrieve a message (streamed via ByteBuffer)</li>
 * <li>{@code dele()} - Mark a message for deletion</li>
 * <li>{@code rset()} - Reset deletion marks</li>
 * <li>{@code top()} - Retrieve message headers and body lines</li>
 * <li>{@code uidl()} - Get unique IDs for messages</li>
 * <li>{@code noop()} - No-op (keep-alive)</li>
 * <li>{@code quit()} - Commit deletions and close</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerPassReplyHandler#handleAuthenticated
 * @see ServerApopReplyHandler#handleAuthenticated
 * @see ServerAuthReplyHandler#handleAuthSuccess
 */
public interface ClientTransactionState {

    /**
     * Sends a STAT command to get mailbox status.
     *
     * @param callback receives the message count and total size
     */
    void stat(ServerStatReplyHandler callback);

    /**
     * Sends a LIST command to list all messages.
     *
     * <p>The handler receives entries one at a time via
     * {@link ServerListReplyHandler#handleListEntry}, followed by
     * {@link ServerListReplyHandler#handleListComplete}.
     *
     * @param callback receives the listing entries
     */
    void list(ServerListReplyHandler callback);

    /**
     * Sends a LIST command for a specific message.
     *
     * @param messageNumber the message number (1-based)
     * @param callback receives the listing
     */
    void list(int messageNumber, ServerListReplyHandler callback);

    /**
     * Sends a RETR command to retrieve a message.
     *
     * <p>Message content is delivered as chunks of ByteBuffer via
     * {@link ServerRetrReplyHandler#handleMessageContent}, with
     * dot-unstuffing handled transparently. The handler receives
     * {@link ServerRetrReplyHandler#handleMessageComplete} when the
     * entire message has been delivered.
     *
     * @param messageNumber the message number (1-based)
     * @param callback receives the message content
     */
    void retr(int messageNumber, ServerRetrReplyHandler callback);

    /**
     * Sends a DELE command to mark a message for deletion.
     *
     * <p>The message is not actually deleted until QUIT is issued.
     *
     * @param messageNumber the message number (1-based)
     * @param callback receives the server's response
     */
    void dele(int messageNumber, ServerDeleReplyHandler callback);

    /**
     * Sends a RSET command to unmark all messages marked for deletion.
     *
     * @param callback receives the server's response
     */
    void rset(ServerRsetReplyHandler callback);

    /**
     * Sends a TOP command to retrieve message headers and body lines.
     *
     * <p>Content is delivered as chunks of ByteBuffer via
     * {@link ServerTopReplyHandler#handleTopContent}, with
     * dot-unstuffing handled transparently.
     *
     * @param messageNumber the message number (1-based)
     * @param lines the number of body lines to retrieve
     * @param callback receives the content
     */
    void top(int messageNumber, int lines,
             ServerTopReplyHandler callback);

    /**
     * Sends a UIDL command to list unique IDs for all messages.
     *
     * <p>The handler receives entries one at a time via
     * {@link ServerUidlReplyHandler#handleUidEntry}, followed by
     * {@link ServerUidlReplyHandler#handleUidComplete}.
     *
     * @param callback receives the unique ID entries
     */
    void uidl(ServerUidlReplyHandler callback);

    /**
     * Sends a UIDL command for a specific message.
     *
     * @param messageNumber the message number (1-based)
     * @param callback receives the unique ID
     */
    void uidl(int messageNumber, ServerUidlReplyHandler callback);

    /**
     * Sends a NOOP command.
     *
     * <p>Useful as a keep-alive to prevent the server from timing out
     * the connection.
     *
     * @param callback receives the server's response
     */
    void noop(ServerNoopReplyHandler callback);

    /**
     * Sends a QUIT command to commit deletions and close the connection.
     *
     * <p>The server enters the UPDATE state, commits any pending
     * deletions, and closes the connection.
     */
    void quit();

}
