/*
 * MessageStartState.java
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
 * Operations for responding to DATA/BDAT initiation.
 * 
 * <p>This interface is provided to {@link RecipientHandler#startMessage}
 * and allows the handler to accept or reject message transfer.
 * 
 * <p>The handler does not need to distinguish between DATA and BDAT
 * transports - this is handled transparently by the connection. Message
 * bytes are written to the pipeline's channel if one was provided.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RecipientHandler#startMessage
 * @see MessageDataHandler
 */
public interface MessageStartState {

    /**
     * Ready to receive message content.
     * 
     * <p>For DATA, sends 354 response. For BDAT, processing begins
     * immediately. Message bytes are written to the pipeline's channel
     * if one was provided via {@link MailFromHandler#getPipeline()}.
     * 
     * <p>The handler receives completion notification via
     * {@link MessageDataHandler#messageComplete}.
     * 
     * @param handler receives message completion notification
     */
    void acceptMessage(MessageDataHandler handler);

    /**
     * Temporarily rejects - storage full (452 response).
     * 
     * @param handler receives next command
     */
    void rejectMessageStorageFull(RecipientHandler handler);

    /**
     * Temporarily rejects - processing error (451 response).
     * 
     * @param handler receives next command
     */
    void rejectMessageProcessingError(RecipientHandler handler);

    /**
     * Permanently rejects message (550 response).
     * 
     * @param message the rejection message
     * @param handler receives next transaction
     */
    void rejectMessage(String message, MailFromHandler handler);

    /**
     * Server is shutting down (421 response).
     * 
     * <p>Closes the connection after sending the response.
     */
    void serverShuttingDown();

}

