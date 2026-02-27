/*
 * ServerListReplyHandler.java
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
 * Handler for LIST command response.
 *
 * <p>This handler supports both single-message and multi-message LIST
 * responses:
 * <ul>
 * <li>Single message ({@code LIST n}): {@link #handleListing} is called
 *     with the message number and size.</li>
 * <li>All messages ({@code LIST}): {@link #handleListEntry} is called
 *     for each message, followed by {@link #handleListComplete}.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientTransactionState#list()
 * @see ClientTransactionState#list(int, ServerListReplyHandler)
 */
public interface ServerListReplyHandler extends ServerReplyHandler {

    /**
     * Called for a single-message LIST response (+OK n s).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param messageNumber the message number
     * @param size the message size in octets
     */
    void handleListing(ClientTransactionState transaction,
                       int messageNumber, long size);

    /**
     * Called for each entry in a multi-message LIST response.
     *
     * @param messageNumber the message number
     * @param size the message size in octets
     */
    void handleListEntry(int messageNumber, long size);

    /**
     * Called when the multi-message LIST response is complete.
     *
     * @param transaction operations to continue in the TRANSACTION state
     */
    void handleListComplete(ClientTransactionState transaction);

    /**
     * Called when the specified message does not exist (-ERR).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param message the server's error message
     */
    void handleNoSuchMessage(ClientTransactionState transaction,
                             String message);

    /**
     * Called when the LIST command fails (-ERR).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param message the server's error message
     */
    void handleError(ClientTransactionState transaction, String message);

}
