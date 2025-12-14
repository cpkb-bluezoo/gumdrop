/*
 * MessageDataHandler.java
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

import java.nio.ByteBuffer;

/**
 * Handler for message content and completion.
 * 
 * <p>Message bytes can be received either via:
 * <ul>
 *   <li>The pipeline's channel if one was provided via
 *       {@link MailFromHandler#getPipeline()}</li>
 *   <li>The {@link #messageContent(ByteBuffer)} method for simple handlers</li>
 * </ul>
 * 
 * <p>The completion notification is sent after:
 * <ul>
 *   <li>All message bytes have been received</li>
 *   <li>The pipeline channel has been closed (if any)</li>
 *   <li>The pipeline's {@code endData()} method has been called (if any)</li>
 * </ul>
 * 
 * <p>The handler should finalize message processing (e.g., wait for
 * pipeline authentication results) and call the appropriate method
 * on the state interface to accept or reject the message.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MessageStartState#acceptMessage
 * @see MessageEndState
 */
public interface MessageDataHandler {

    /**
     * Called with message content as it arrives.
     * 
     * <p>This method is called incrementally as message bytes are received.
     * The buffer contains raw RFC 5322 message bytes (headers and body).
     * For DATA transfer, dot-stuffing has already been removed.
     * 
     * <p>If a pipeline is provided via {@link MailFromHandler#getPipeline()},
     * the pipeline receives the bytes instead and this method is not called.
     * 
     * <p>The buffer is temporary and should be consumed or copied before
     * this method returns.
     * 
     * @param content the message content bytes
     */
    void messageContent(ByteBuffer content);

    /**
     * Called when message transfer is complete.
     * 
     * <p>This is called after all message bytes have been received
     * (either via DATA terminator CRLF.CRLF or final BDAT LAST chunk).
     * The pipeline's {@code endData()} method has been called before
     * this notification.
     * 
     * <p>The handler should finalize message processing and call the
     * appropriate method on the state interface:
     * <ul>
     *   <li>{@link MessageEndState#acceptMessageDelivery} to accept</li>
     *   <li>{@link MessageEndState#rejectMessageTemporary} for temp failure</li>
     *   <li>{@link MessageEndState#rejectMessagePermanent} for perm failure</li>
     * </ul>
     * 
     * @param state operations for finalizing the message
     */
    void messageComplete(MessageEndState state);

    /**
     * Called if the message transfer is aborted mid-stream.
     * 
     * <p>This can happen if:
     * <ul>
     *   <li>The client disconnects during transfer</li>
     *   <li>The message exceeds size limits</li>
     *   <li>A BDAT chunk contains malformed data</li>
     *   <li>An I/O error occurs</li>
     * </ul>
     * 
     * <p>No response is expected after this notification.
     */
    void messageAborted();

}

