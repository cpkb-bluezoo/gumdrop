/*
 * RecipientState.java
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
 * Operations for responding to RCPT TO.
 * 
 * <p>This interface is provided to {@link RecipientHandler#rcptTo} and
 * allows the handler to accept or reject the recipient.
 * 
 * <p>After at least one recipient is accepted, the client can proceed
 * to send message content via DATA or BDAT.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RecipientHandler#rcptTo
 */
public interface RecipientState {

    /**
     * Accepts the recipient (250 response).
     * 
     * @param handler receives more recipients or message start
     */
    void acceptRecipient(RecipientHandler handler);

    /**
     * Accepts with forward notification (251 response).
     * 
     * <p>Indicates the message will be forwarded to another address.
     * 
     * @param forwardPath where the message will be forwarded
     * @param handler receives more recipients or message start
     */
    void acceptRecipientForward(String forwardPath, RecipientHandler handler);

    /**
     * Temporarily rejects - mailbox unavailable (450 response).
     * 
     * @param handler receives more recipients
     */
    void rejectRecipientUnavailable(RecipientHandler handler);

    /**
     * Temporarily rejects - system error (451 response).
     * 
     * @param handler receives more recipients
     */
    void rejectRecipientSystemError(RecipientHandler handler);

    /**
     * Temporarily rejects - storage full (452 response).
     * 
     * @param handler receives more recipients
     */
    void rejectRecipientStorageFull(RecipientHandler handler);

    /**
     * Permanently rejects - mailbox does not exist (550 response).
     * 
     * @param handler receives more recipients
     */
    void rejectRecipientNotFound(RecipientHandler handler);

    /**
     * Permanently rejects - user not local (551 response).
     * 
     * @param handler receives more recipients
     */
    void rejectRecipientNotLocal(RecipientHandler handler);

    /**
     * Permanently rejects - quota exceeded (552 response).
     * 
     * @param handler receives more recipients
     */
    void rejectRecipientQuota(RecipientHandler handler);

    /**
     * Permanently rejects - invalid mailbox name (553 response).
     * 
     * @param handler receives more recipients
     */
    void rejectRecipientInvalid(RecipientHandler handler);

    /**
     * Permanently rejects - relay denied (551 response).
     * 
     * @param handler receives more recipients
     */
    void rejectRecipientRelayDenied(RecipientHandler handler);

    /**
     * Permanently rejects - policy violation (553 response).
     * 
     * @param message custom rejection message
     * @param handler receives more recipients
     */
    void rejectRecipientPolicy(String message, RecipientHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends a 421 response indicating the server is shutting down
     * and closes the connection.
     */
    void serverShuttingDown();

}

