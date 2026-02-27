/*
 * ServerRetrReplyHandler.java
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

import java.nio.ByteBuffer;

/**
 * Handler for RETR command response.
 *
 * <p>Message content is streamed as chunks of ByteBuffer. Dot-unstuffing
 * is handled transparently by the protocol handler - the handler receives
 * decoded message content.
 *
 * <p>{@link #handleMessageContent} may be called multiple times as data
 * arrives from the network. {@link #handleMessageComplete} is called once
 * when the terminating dot line is received.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientTransactionState#retr
 */
public interface ServerRetrReplyHandler extends ServerReplyHandler {

    /**
     * Called with a chunk of message content.
     *
     * <p>This method may be called multiple times. The content has been
     * dot-unstuffed and does not include the terminating dot line.
     * The ByteBuffer is only valid for the duration of this call.
     *
     * @param content a chunk of decoded message content
     */
    void handleMessageContent(ByteBuffer content);

    /**
     * Called when the entire message has been received.
     *
     * @param transaction operations to continue in the TRANSACTION state
     */
    void handleMessageComplete(ClientTransactionState transaction);

    /**
     * Called when the specified message does not exist (-ERR).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param message the server's error message
     */
    void handleNoSuchMessage(ClientTransactionState transaction,
                             String message);

    /**
     * Called when the specified message has been marked deleted (-ERR).
     *
     * @param transaction operations to continue in the TRANSACTION state
     * @param message the server's error message
     */
    void handleMessageDeleted(ClientTransactionState transaction,
                              String message);

}
