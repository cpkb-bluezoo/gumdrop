/*
 * ClientEnvelopeReady.java
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
 * Operations available after at least one recipient is accepted.
 * 
 * <p>This interface is provided to the handler in
 * {@link ServerRcptToReplyHandler#handleRcptToOk} after a recipient is
 * accepted, and includes the {@code data()} method to proceed to message
 * content.
 * 
 * <p>The handler can:
 * <ul>
 * <li>Add more recipients with {@code rcptTo()}</li>
 * <li>Proceed to send message content with {@code data()}</li>
 * <li>Abort the transaction with {@code rset()}</li>
 * <li>Close the connection with {@code quit()}</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerRcptToReplyHandler#handleRcptToOk
 * @see ClientEnvelopeState
 */
public interface ClientEnvelopeReady extends ClientEnvelopeState {

    /**
     * Proceeds to send message content.
     * 
     * <p>Sends the DATA command. On success (354), the handler receives
     * a {@link ClientMessageData} interface for writing message content.
     * 
     * @param callback receives the server's response
     */
    void data(ServerDataReplyHandler callback);

}

