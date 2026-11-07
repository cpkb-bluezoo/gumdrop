/*
 * ClientConnected.java
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

import org.bluezoo.gumdrop.Endpoint;

/**
 * Application handler for a newly accepted NNTP connection: the first
 * stage of the server pipeline. It decides whether to accept the
 * connection and with what greeting, through the {@link ConnectedState}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ClientConnected {

    /**
     * A client has connected (and, on an implicit TLS listener, completed
     * its handshake). The handler must respond through the state.
     *
     * @param state the connection state, used to accept or reject
     * @param endpoint the connection
     */
    void connected(ConnectedState state, Endpoint endpoint);

    /**
     * The connection has closed.
     */
    void disconnected();

}
