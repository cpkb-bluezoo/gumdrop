/*
 * DataEndCallback.java
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

package org.bluezoo.gumdrop.smtp;

/**
 * Callback interface for delivering asynchronous message processing completion results.
 * <p>
 * This interface enables non-blocking SMTP connection handling by allowing
 * message processing to be performed asynchronously. The handler can perform
 * content analysis, virus scanning, spam filtering, storage operations, or other
 * time-consuming operations without blocking the connection thread.
 * 
 * <p>The callback method will be invoked with the processing result, and the
 * SMTP connection will automatically send the appropriate response code
 * to the client based on the result.
 * 
 * <p>This callback handles the final phase of the DATA command:
 * <ol>
 * <li><strong>DATA Initiation</strong> - Server decides whether to accept message data (DataStartCallback)</li>
 * <li><strong>Message Content</strong> - Server receives message data via messageContent() calls</li>
 * <li><strong>DATA Completion</strong> - Server processes complete message (this callback)</li>
 * </ol>
 * 
 * <p><strong>Thread Safety:</strong> Implementations must be thread-safe as
 * the callback may be invoked from different threads depending on how the
 * message processing is implemented (thread pools, async I/O, etc.).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DataEndReply
 * @see DataStartCallback
 * @see SMTPConnectionHandler#endData(SMTPConnectionMetadata, DataEndCallback)
 */

public interface DataEndCallback {

    /**
     * Receives the result of message processing completion.
     * <p>
     * This method is called asynchronously once the processing of
     * a complete message has finished. The SMTP connection will automatically
     * generate the appropriate SMTP response based on the result.
     * 
     * <p>If the result is ACCEPT or ACCEPT_WITH_MODIFICATIONS, the connection
     * will send a 250 response and return to the MAIL state, ready for the
     * next transaction. For any rejection result, the connection will send
     * the appropriate error response and reset the transaction state.
     * 
     * <p>The implementation should not assume which thread this method will
     * be called from, and should not perform any blocking operations.
     * 
     * @param result the processing result for the complete message
     */
    void dataEndReply(DataEndReply result);

}
