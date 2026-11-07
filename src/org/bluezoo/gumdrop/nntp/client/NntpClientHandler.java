/*
 * NntpClientHandler.java
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

package org.bluezoo.gumdrop.nntp.client;

/**
 * Receives the life cycle events of an {@link NntpClient} connection. All
 * methods are called on the connection's event loop, so they should return
 * quickly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface NntpClientHandler {

    /**
     * The connection is ready: the server has greeted, TLS has been set up
     * and the client logged in, as configured. The session accepts commands.
     *
     * @param session the session
     * @param greeting what the server said
     */
    void connected(NntpSession session, NntpGreeting greeting);

    /**
     * The connection failed: it could not be made, TLS failed, a required
     * STARTTLS was not offered, the credentials were rejected, a command
     * timed out, or the transport broke. Commands in flight have already
     * been told through their own callbacks.
     *
     * @param cause the failure
     */
    void error(Exception cause);

    /**
     * The connection closed, after QUIT or because the server closed it.
     */
    void disconnected();

}
