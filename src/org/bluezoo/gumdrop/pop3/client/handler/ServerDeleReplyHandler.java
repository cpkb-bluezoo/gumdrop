/*
 * ServerDeleReplyHandler.java
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
 * Handler for DELE command response.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientTransactionState#dele
 */
public interface ServerDeleReplyHandler extends ServerReplyHandler {

    /**
     * Called when the message is marked for deletion (+OK).
     *
     * @param transaction operations to continue in the TRANSACTION state
     */
    void handleDeleted(ClientTransactionState transaction);

    /**
     * Called when the specified message does not exist (-ERR).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param message the server's error message
     */
    void handleNoSuchMessage(ClientTransactionState transaction,
                             String message);

    /**
     * Called when the message was already marked for deletion (-ERR).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param message the server's error message
     */
    void handleAlreadyDeleted(ClientTransactionState transaction,
                              String message);

}
