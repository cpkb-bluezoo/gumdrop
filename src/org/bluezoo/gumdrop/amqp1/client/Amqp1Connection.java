/*
 * Amqp1Connection.java
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
import org.bluezoo.gumdrop.amqp1.codec.Open;

/**
 * An open AMQP 1.0 connection. Begin a session to do anything useful:
 * the connection itself carries only session management, keepalives and
 * shutdown.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1Connection {

    /** Default session window size, in transfer frames. */
    long DEFAULT_WINDOW = 2048;

    /**
     * The server's {@code open} performative.
     *
     * @return the peer's open
     */
    Open getPeerOpen();

    /**
     * The largest frame the server will accept, in octets: the
     * server's advertised max-frame-size.
     *
     * @return the peer's max-frame-size
     */
    long getPeerMaxFrameSize();

    /**
     * The highest channel number usable on this connection: the lower of
     * the two peers' channel-max values.
     *
     * @return the negotiated channel-max
     */
    int getChannelMax();

    /**
     * Begins a session with default window sizes
     * ({@link #DEFAULT_WINDOW}).
     *
     * @param handler receives the outcome and later events for the session
     * @throws IllegalStateException if every channel number is in use
     */
    void beginSession(Amqp1SessionHandler handler);

    /**
     * Begins a session with explicit parameters.
     *
     * @param begin the {@code begin} to send; its remote-channel must
     *      be unset, as this client initiates the session
     * @param handler receives the outcome and later events for the session
     * @throws IllegalStateException if every channel number is in use
     * @throws IllegalArgumentException if {@code begin} has a remote-channel
     */
    void beginSession(Begin begin, Amqp1SessionHandler handler);

    /**
     * Closes the connection. The peer's answering {@code close} is
     * reported through {@link Amqp1ConnectionReady#onConnectionClosed}.
     * Sessions still active are reported ended.
     *
     * @param error why the connection is being closed, or {@code null}
     *      for a normal close
     */
    void close(Amqp1Error error);
}
