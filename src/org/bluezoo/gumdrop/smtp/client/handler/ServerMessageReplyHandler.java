/*
 * ServerMessageReplyHandler.java
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
 * Handler for end-of-message response.
 * 
 * <p>This handler receives the server's response after the message content
 * has been sent (after {@code endMessage()}). On success (250), the handler
 * receives a queue ID (if provided by the server) and a {@link ClientSession}
 * interface to send additional messages or quit.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientMessageData#endMessage
 */
public interface ServerMessageReplyHandler extends ServerReplyHandler {

    /**
     * Called when the message is accepted (250).
     * 
     * <p>The message has been queued for delivery. The handler can send
     * another message or quit.
     * 
     * @param queueId the server-assigned queue ID (may be null if not provided)
     * @param session operations to send another message or quit
     */
    void handleMessageAccepted(String queueId, ClientSession session);

    /**
     * Called when the message is temporarily rejected (4xx).
     * 
     * <p>The message was not accepted but may succeed if retried later.
     * Common reasons include:
     * <ul>
     * <li>451 - Local processing error</li>
     * <li>452 - Insufficient storage</li>
     * </ul>
     * 
     * @param session operations to start a new transaction or quit
     */
    void handleTemporaryFailure(ClientSession session);

    /**
     * Called when the message is permanently rejected (5xx).
     * 
     * <p>The message content is not acceptable. Common reasons include:
     * <ul>
     * <li>550 - Mailbox unavailable</li>
     * <li>552 - Message size exceeds limit</li>
     * <li>554 - Transaction failed</li>
     * </ul>
     * 
     * @param message the server's rejection message
     * @param session operations to start a new transaction or quit
     */
    void handlePermanentFailure(String message, ClientSession session);

}

