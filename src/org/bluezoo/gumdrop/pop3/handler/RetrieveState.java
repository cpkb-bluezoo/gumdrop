/*
 * RetrieveState.java
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

package org.bluezoo.gumdrop.pop3.handler;

import java.nio.channels.ReadableByteChannel;

/**
 * Operations available when responding to a message retrieval request.
 * 
 * <p>This interface is provided to {@link TransactionHandler#retrieveMessage} and
 * allows the handler to send message content.
 * 
 * <p>The message content is sent with byte-stuffing per RFC 1939 Section 3:
 * lines beginning with '.' are prefixed with an additional '.', and the
 * message is terminated with a line containing only '.'.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TransactionHandler#retrieveMessage
 */
public interface RetrieveState {

    /**
     * Sends the message content.
     * 
     * <p>Sends a +OK response with the message size, then streams the
     * message content from the channel with byte-stuffing, and terminates
     * with a line containing only '.'.
     * 
     * @param size the message size in octets
     * @param content the message content channel
     * @param handler continues receiving transaction commands after send completes
     */
    void sendMessage(long size, ReadableByteChannel content, TransactionHandler handler);

    /**
     * The requested message does not exist.
     * 
     * @param handler continues receiving transaction commands
     */
    void noSuchMessage(TransactionHandler handler);

    /**
     * The requested message has been deleted.
     * 
     * @param handler continues receiving transaction commands
     */
    void messageDeleted(TransactionHandler handler);

    /**
     * Reports an error retrieving the message.
     * 
     * @param message the error message
     * @param handler continues receiving transaction commands
     */
    void error(String message, TransactionHandler handler);

}

