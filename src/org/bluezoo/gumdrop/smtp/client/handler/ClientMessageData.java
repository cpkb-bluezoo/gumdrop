/*
 * ClientMessageData.java
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

package org.bluezoo.gumdrop.smtp.client.handler;

import java.nio.ByteBuffer;

/**
 * Operations for writing message content.
 * 
 * <p>This interface is provided to the handler in
 * {@link ServerDataReplyHandler#handleReadyForData} when the connection
 * is ready to accept message content.
 * 
 * <p>The handler should:
 * <ol>
 * <li>Write the message content using {@code writeContent()} (can be called multiple times)</li>
 * <li>Call {@code endMessage()} to complete the transaction</li>
 * </ol>
 * 
 * <p>The message content should be a valid RFC 5322 formatted message.
 * The connection transparently handles the underlying protocol:
 * <ul>
 * <li>If the server supports CHUNKING (BDAT), content is streamed as BDAT chunks
 *     with no server roundtrip delay or dot-stuffing overhead</li>
 * <li>Otherwise, traditional DATA mode is used with automatic dot-stuffing</li>
 * </ul>
 * 
 * <p>The handler writes raw message bytes in either case - the protocol
 * differences are handled automatically by the connection.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerDataReplyHandler#handleReadyForData
 */
public interface ClientMessageData {

    /**
     * Writes message content to the server.
     * 
     * <p>This method can be called multiple times to stream large messages
     * without buffering the entire content in memory. The connection handles
     * protocol details automatically (dot-stuffing for DATA, BDAT chunks for
     * CHUNKING).
     * 
     * <p>The content should be part of a valid RFC 5322 formatted message,
     * with CRLF line endings.
     * 
     * @param content the message content bytes
     */
    void writeContent(ByteBuffer content);

    /**
     * Completes the message and submits for delivery.
     * 
     * <p>Signals the end of message content and waits for the server's
     * response. On success (250), the handler receives a queue ID and a
     * {@link ClientSession} interface for sending more messages.
     * 
     * @param callback receives the server's response
     */
    void endMessage(ServerMessageReplyHandler callback);

}

