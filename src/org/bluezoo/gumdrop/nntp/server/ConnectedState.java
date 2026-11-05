/*
 * ConnectedState.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.nntp.server;

/**
 * Responses available to a {@link ClientConnected} handler. RFC 3977
 * section 5.1: the greeting is 200 if posting is allowed and 201 if it is
 * not; 400 and 502 refuse the connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ConnectedState {

    /**
     * Accepts the connection.
     *
     * @param greeting the text after the response code
     * @param postingAllowed whether the client may be told it can post
     * @param handler the handler for the reader commands that follow
     */
    void acceptConnection(String greeting, boolean postingAllowed,
                          ReaderHandler handler);

    /**
     * Rejects the connection with the standard message and closes it.
     */
    void rejectConnection();

    /**
     * Rejects the connection with a message and closes it.
     *
     * @param message the text after the response code
     */
    void rejectConnection(String message);

    /**
     * Tells the client the server is shutting down and closes the connection.
     */
    void serverShuttingDown();

}
