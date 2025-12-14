/*
 * MessageEndState.java
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

package org.bluezoo.gumdrop.smtp.handler;

/**
 * Operations for finalizing message acceptance.
 * 
 * <p>This interface is provided to {@link MessageDataHandler#messageComplete}
 * and allows the handler to accept or reject the completed message.
 * 
 * <p>After accepting or rejecting, the connection returns to the mail-from
 * ready state where another transaction can begin.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MessageDataHandler#messageComplete
 * @see MailFromHandler
 */
public interface MessageEndState {

    /**
     * Message accepted for delivery (250 response).
     * 
     * @param queueId optional queue identifier for tracking (may be null)
     * @param handler receives next transaction
     */
    void acceptMessageDelivery(String queueId, MailFromHandler handler);

    /**
     * Temporarily rejects message (450 response).
     * 
     * <p>Used when the message cannot be accepted right now but might
     * be acceptable later (e.g., downstream server unavailable).
     * 
     * @param message the rejection message
     * @param handler receives next transaction
     */
    void rejectMessageTemporary(String message, MailFromHandler handler);

    /**
     * Permanently rejects message (550 response).
     * 
     * <p>Used when the message cannot be delivered (e.g., content
     * filtering rejection, authentication failure).
     * 
     * @param message the rejection message
     * @param handler receives next transaction
     */
    void rejectMessagePermanent(String message, MailFromHandler handler);

    /**
     * Rejects for policy violation (553 response).
     * 
     * <p>Used for policy-based rejections (e.g., DMARC reject policy).
     * 
     * @param message the rejection message
     * @param handler receives next transaction
     */
    void rejectMessagePolicy(String message, MailFromHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends a 421 response indicating the server is shutting down
     * and closes the connection.
     */
    void serverShuttingDown();

}

