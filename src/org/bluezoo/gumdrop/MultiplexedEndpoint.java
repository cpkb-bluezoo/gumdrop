/*
 * MultiplexedEndpoint.java
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
 * An endpoint that supports multiple independent streams.
 *
 * <p>QUIC provides native transport-layer stream multiplexing: a single
 * QUIC connection can carry many concurrent bidirectional or unidirectional
 * streams. This interface exposes that capability.
 *
 * <p>Each stream is itself an {@link Endpoint} that protocol handlers
 * interact with identically to a TCP connection. The MultiplexedEndpoint
 * represents the connection as a whole and provides methods to open new
 * outgoing streams and accept incoming streams from the peer.
 *
 * <p>Most protocol handlers (SMTP, IMAP, DNS-over-QUIC) use a single
 * stream and interact with a plain {@link Endpoint}. Only HTTP/3 and
 * WebTransport use MultiplexedEndpoint directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see StreamAcceptHandler
 * @see Endpoint
 */
public interface MultiplexedEndpoint extends Endpoint {

    /**
     * Opens a new outgoing bidirectional stream.
     *
     * <p>The returned Endpoint represents the new stream. The provided
     * handler will receive data and lifecycle events for this stream.
     *
     * @param handler the handler for the new stream
     * @return an Endpoint for the new stream
     */
    Endpoint openStream(ProtocolHandler handler);

    /**
     * Registers a handler to accept incoming streams from the peer.
     *
     * <p>When the peer opens a new stream, the StreamAcceptHandler's
     * {@link StreamAcceptHandler#acceptStream(Endpoint)} method is called
     * to obtain an ProtocolHandler for that stream.
     *
     * @param handler the handler that will accept incoming streams
     */
    void setStreamAcceptHandler(StreamAcceptHandler handler);
}
