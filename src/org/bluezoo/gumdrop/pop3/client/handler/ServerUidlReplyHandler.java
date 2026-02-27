/*
 * ServerUidlReplyHandler.java
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
 * Handler for UIDL command response.
 *
 * <p>This handler supports both single-message and multi-message UIDL
 * responses:
 * <ul>
 * <li>Single message ({@code UIDL n}): {@link #handleUid} is called
 *     with the message number and unique ID.</li>
 * <li>All messages ({@code UIDL}): {@link #handleUidEntry} is called
 *     for each message, followed by {@link #handleUidComplete}.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientTransactionState#uidl()
 * @see ClientTransactionState#uidl(int, ServerUidlReplyHandler)
 */
public interface ServerUidlReplyHandler extends ServerReplyHandler {

    /**
     * Called for a single-message UIDL response (+OK n uid).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param messageNumber the message number
     * @param uid the unique ID
     */
    void handleUid(ClientTransactionState transaction,
                   int messageNumber, String uid);

    /**
     * Called for each entry in a multi-message UIDL response.
     *
     * @param messageNumber the message number
     * @param uid the unique ID
     */
    void handleUidEntry(int messageNumber, String uid);

    /**
     * Called when the multi-message UIDL response is complete.
     *
     * @param transaction operations to continue in the TRANSACTION state
     */
    void handleUidComplete(ClientTransactionState transaction);

    /**
     * Called when the specified message does not exist (-ERR).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param message the server's error message
     */
    void handleNoSuchMessage(ClientTransactionState transaction,
                             String message);

    /**
     * Called when the UIDL command fails (-ERR).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param message the server's error message
     */
    void handleError(ClientTransactionState transaction, String message);

}
