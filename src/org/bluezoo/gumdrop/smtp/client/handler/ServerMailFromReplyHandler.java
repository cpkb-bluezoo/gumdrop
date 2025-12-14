/*
 * ServerMailFromReplyHandler.java
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
 * Handler for MAIL FROM command response.
 * 
 * <p>This handler receives the server's response to a MAIL FROM command.
 * On success, the handler receives a {@link ClientEnvelope} interface for
 * adding recipients to the mail transaction.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientSession#mailFrom
 * @see ClientEnvelope
 */
public interface ServerMailFromReplyHandler extends ServerReplyHandler {

    /**
     * Called when the sender is accepted (250).
     * 
     * <p>The handler should proceed to add recipients using the provided
     * envelope interface.
     * 
     * @param envelope operations to add recipients to the transaction
     */
    void handleMailFromOk(ClientEnvelope envelope);

    /**
     * Called when the sender is temporarily rejected (4xx).
     * 
     * <p>The handler can retry with the same or different sender, or abort.
     * Common reasons include:
     * <ul>
     * <li>450 - Mailbox temporarily unavailable</li>
     * <li>451 - Local processing error</li>
     * <li>452 - Insufficient storage</li>
     * </ul>
     * 
     * @param session operations to retry or quit
     */
    void handleTemporaryFailure(ClientSession session);

    /**
     * Called when the sender is permanently rejected (5xx).
     * 
     * <p>The sender address is not acceptable. Common reasons include:
     * <ul>
     * <li>550 - Sender rejected by policy</li>
     * <li>553 - Mailbox name syntax not allowed</li>
     * <li>555 - Parameters not recognized</li>
     * </ul>
     * 
     * @param message the server's rejection message
     */
    void handlePermanentFailure(String message);

}

