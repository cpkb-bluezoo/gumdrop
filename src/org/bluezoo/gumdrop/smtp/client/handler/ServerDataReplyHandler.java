/*
 * ServerDataReplyHandler.java
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

/**
 * Handler for DATA command response.
 * 
 * <p>This handler receives the server's response to a DATA command. On success
 * (354), the handler receives a {@link ClientMessageData} interface for writing
 * message content.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientEnvelopeReady#data
 * @see ClientMessageData
 */
public interface ServerDataReplyHandler extends ServerReplyHandler {

    /**
     * Called when the server is ready for message content (354).
     * 
     * <p>The handler should write the message content using the provided
     * interface, then call {@code endMessage()} to complete the transaction.
     * 
     * @param data operations to write message content
     */
    void handleReadyForData(ClientMessageData data);

    /**
     * Called when DATA is temporarily rejected (4xx).
     * 
     * <p>The handler can retry or abort the transaction.
     * 
     * @param envelope operations to retry or abort
     */
    void handleTemporaryFailure(ClientEnvelopeReady envelope);

    /**
     * Called when DATA is permanently rejected (5xx).
     * 
     * <p>The transaction cannot proceed. Common reasons include:
     * <ul>
     * <li>503 - No valid recipients (shouldn't happen with proper state machine)</li>
     * <li>554 - Transaction failed</li>
     * </ul>
     * 
     * @param message the server's error message
     */
    void handlePermanentFailure(String message);

}

