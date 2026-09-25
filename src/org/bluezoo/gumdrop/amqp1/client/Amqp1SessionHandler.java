/*
 * Amqp1SessionHandler.java
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

package org.bluezoo.gumdrop.amqp1.client;

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Begin;

/**
 * Receives the life-cycle events of one session.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1SessionHandler {

    /**
     * The server accepted the session.
     *
     * @param session the active session
     * @param peerBegin the server's {@code begin}
     */
    void handleBegun(Amqp1Session session, Begin peerBegin);

    /**
     * The session is over: the server ended it (possibly with an error,
     * including immediately after {@link #handleBegun}), the server
     * acknowledged an end requested with {@link Amqp1Session#end}, or
     * the connection went away.
     *
     * @param error the error reported, or {@code null} for a normal end
     */
    void handleEnded(Amqp1Error error);
}
