/*
 * ServerStatReplyHandler.java
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
 * Handler for STAT command response.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientTransactionState#stat
 */
public interface ServerStatReplyHandler extends ServerReplyHandler {

    /**
     * Called when the server returns the mailbox status (+OK).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param messageCount the number of messages in the mailbox
     * @param totalSize the total size of all messages in octets
     */
    void handleStat(ClientTransactionState transaction,
                    int messageCount, long totalSize);

    /**
     * Called when the STAT command fails (-ERR).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param message the server's error message
     */
    void handleError(ClientTransactionState transaction, String message);

}
