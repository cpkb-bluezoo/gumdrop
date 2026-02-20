/*
 * StreamAcceptHandler.java
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

package org.bluezoo.gumdrop;

/**
 * Callback for accepting incoming streams on a {@link MultiplexedEndpoint}.
 *
 * <p>When a remote peer opens a new stream on a multiplexed connection
 * (e.g., a QUIC connection), this handler is called to provide an
 * {@link ProtocolHandler} for the new stream.
 *
 * <p>This is the QUIC equivalent of a TCP accept listener: where a TCP
 * server accepts new connections, a QUIC server accepts new streams
 * within an existing connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MultiplexedEndpoint#setStreamAcceptHandler(StreamAcceptHandler)
 */
public interface StreamAcceptHandler {

    /**
     * Called when the peer opens a new stream.
     *
     * <p>The implementation should return an ProtocolHandler that will
     * receive data and lifecycle events for this stream. If the stream
     * should be rejected, the implementation should close the stream
     * endpoint and may return null.
     *
     * @param stream the endpoint representing the new stream
     * @return the handler for the new stream, or null to reject it
     */
    ProtocolHandler acceptStream(Endpoint stream);
}
