/*
 * ClientConnection.java
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

package org.bluezoo.gumdrop.amqp.client.handler;

/**
 * An open AMQP connection. Open one or more channels to do anything
 * useful — the connection itself carries no traffic other than channel
 * management, heartbeats, and shutdown.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ClientConnection {

    /**
     * Opens a new channel.
     *
     * @param channelId the channel number to open (caller-assigned, must
     *      be unique among currently-open channels on this connection,
     *      1 to the negotiated channel-max)
     * @param handler receives {@code channel.open-ok}
     */
    void channelOpen(int channelId, ServerChannelOpenHandler handler);

    /**
     * Closes the connection gracefully.
     *
     * @param replyCode an AMQP reply code (200 for normal closure)
     * @param replyText a human-readable reason
     * @param handler receives {@code connection.close-ok}
     */
    void close(int replyCode, String replyText, ServerCloseHandler handler);
}
