/*
 * ServerRcptToReplyHandler.java
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
 * Handler for RCPT TO command response.
 * 
 * <p>This handler receives the server's response to a RCPT TO command. The
 * interface returned depends on whether the recipient was accepted:
 * 
 * <ul>
 * <li>On success (250/251/252), the handler receives {@link ClientEnvelopeReady}
 *     which includes the {@code data()} method to proceed to message content.</li>
 * <li>On recipient rejection (4xx/5xx), the handler receives {@link ClientEnvelopeState}
 *     which allows adding more recipients but may not allow {@code data()} if no
 *     recipients have been accepted yet.</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientEnvelope#rcptTo
 * @see ClientEnvelopeReady
 * @see ClientEnvelopeState
 */
public interface ServerRcptToReplyHandler extends ServerReplyHandler {

    /**
     * Called when the recipient is accepted (250, 251, or 252).
     * 
     * <p>The handler can add more recipients or proceed to send message
     * content using the {@code data()} method.
     * 
     * <p>Response codes:
     * <ul>
     * <li>250 - Recipient OK</li>
     * <li>251 - User not local; will forward</li>
     * <li>252 - Cannot VRFY user but will accept message</li>
     * </ul>
     * 
     * @param envelope operations to add more recipients or send data
     */
    void handleRcptToOk(ClientEnvelopeReady envelope);

    /**
     * Called when the recipient is temporarily rejected (4xx).
     * 
     * <p>The handler can try another recipient or abort. The state interface
     * allows {@code data()} only if other recipients have been accepted.
     * 
     * @param state operations to add more recipients or (if any accepted) send data
     */
    void handleTemporaryFailure(ClientEnvelopeState state);

    /**
     * Called when the recipient is permanently rejected (5xx).
     * 
     * <p>This specific recipient is not acceptable. Common reasons include:
     * <ul>
     * <li>550 - User not found</li>
     * <li>551 - User not local</li>
     * <li>552 - Mailbox full</li>
     * <li>553 - Mailbox name not allowed</li>
     * </ul>
     * 
     * <p>The handler can try another recipient or abort. The state interface
     * allows {@code data()} only if other recipients have been accepted.
     * 
     * @param state operations to add more recipients or (if any accepted) send data
     */
    void handleRecipientRejected(ClientEnvelopeState state);

}

