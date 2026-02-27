/*
 * ServerTopReplyHandler.java
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
 * Handler for TOP command response.
 *
 * <p>Content is streamed as chunks of ByteBuffer, identical to RETR.
 * Dot-unstuffing is handled transparently.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientTransactionState#top
 */
public interface ServerTopReplyHandler extends ServerReplyHandler {

    /**
     * Called with a chunk of TOP content (headers + body lines).
     *
     * <p>The ByteBuffer is only valid for the duration of this call.
     *
     * @param content a chunk of decoded content
     */
    void handleTopContent(ByteBuffer content);

    /**
     * Called when the TOP response is complete.
     *
     * @param transaction operations to continue in the TRANSACTION state
     */
    void handleTopComplete(ClientTransactionState transaction);

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
