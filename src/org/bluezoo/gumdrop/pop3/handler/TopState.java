/*
 * TopState.java
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
 * Operations available when responding to a TOP command.
 * 
 * <p>This interface is provided to {@link TransactionHandler#top} and
 * allows the handler to send message headers and limited body content.
 * 
 * <p>TOP returns the message headers followed by a blank line and then
 * the first N lines of the message body (RFC 1939 Section 7).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TransactionHandler#top
 */
public interface TopState {

    /**
     * Sends the message headers and N body lines.
     * 
     * <p>Sends a +OK response, then streams the content from the channel
     * with byte-stuffing, and terminates with a line containing only '.'.
     * 
     * @param content the message headers + N body lines channel
     * @param handler continues receiving transaction commands after send completes
     */
    void sendTop(ReadableByteChannel content, TransactionHandler handler);

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

