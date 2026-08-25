/*
 * ProtocolHandler.java
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

import java.nio.ByteBuffer;

/**
 * Callback interface for protocol handlers.
 *
 * <p>Protocol implementations (SMTP, IMAP, HTTP, etc.) implement this
 * interface to receive events from an {@link Endpoint}.
 *
 * <p>All methods are called on the Endpoint's SelectorLoop thread. They
 * must not perform blocking operations.
 *
 * <p>The lifecycle for a typical server-side handler is:
 * <ol>
 * <li>{@link #connected(Endpoint)} -- endpoint is ready for traffic</li>
 * <li>{@link #securityEstablished(SecurityInfo)} -- if TLS was negotiated
 *     (called immediately before {@code connected} for QUIC, after STARTTLS
 *     for TCP)</li>
 * <li>{@link #receive(ByteBuffer)} -- called for each chunk of data</li>
 * <li>{@link #readFinished()} -- peer finished sending on a QUIC stream
 *     (the endpoint may still be open for sending)</li>
 * <li>{@link #disconnected()} -- connection or stream fully closed</li>
 * </ol>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint
 * @see SecurityInfo
 */
public interface ProtocolHandler {

    /**
     * Called when plaintext application data is received from the peer.
     *
     * <p>The buffer is in read mode (position at data start, limit at data
     * end). The handler should consume as much data as it can. After this
     * method returns, any unconsumed data (between the buffer's position and
     * limit) will be preserved for the next call.
     *
     * @param data the application data received
     */
    void receive(ByteBuffer data);

    /**
     * Called when the endpoint is established and ready for protocol traffic.
     *
     * <p>The endpoint reference passed here should be stored by the handler
     * for sending data and querying connection state.
     *
     * @param endpoint the endpoint that is now connected
     */
    void connected(Endpoint endpoint);

    /**
     * Called when the peer has finished sending on a QUIC stream (a STREAM
     * frame with FIN was received). The endpoint remains usable for sending
     * until the local side calls {@link Endpoint#close()} or the stream is
     * reset (RFC 9000 section 3.5).
     *
     * <p>Default implementation is a no-op. TCP endpoints never invoke
     * this; they signal peer closure via {@link #disconnected()} instead.
     */
    default void readFinished() {
    }

    /**
     * Called when the connection or stream is fully closed and the endpoint
     * is no longer usable.
     *
     * <p>On QUIC streams this is invoked on RESET_STREAM or connection
     * teardown, not on a peer STREAM FIN -- see {@link #readFinished()}.
     * On TCP, this is invoked when the peer closes the connection.
     *
     * <p>Protocol handlers should perform any cleanup here.
     */
    void disconnected();

    /**
     * Called when the security layer becomes active.
     *
     * <p>For TCP with STARTTLS, this is called after the TLS handshake
     * completes. For QUIC (always secure), this is called before
     * {@link #connected(Endpoint)}. For initially-secure TCP, this is
     * also called before {@link #connected(Endpoint)}.
     *
     * <p>Protocol handlers that need to reset state after STARTTLS
     * (e.g., SMTP EHLO re-issue) should do so here.
     *
     * @param info details about the negotiated security parameters
     */
    void securityEstablished(SecurityInfo info);

    /**
     * Called when an unrecoverable error occurs on the endpoint.
     *
     * <p>This covers I/O errors, TLS handshake failures, and
     * connection failures for client-initiated endpoints.
     * The endpoint may or may not be usable after this call.
     *
     * @param cause the exception that caused the error
     */
    void error(Exception cause);

    /**
     * Called when an unreliable DATAGRAM (RFC 9221) is received on the
     * QUIC connection this handler was registered on via
     * {@code QuicConnection.setDatagramHandler}. The default is a no-op.
     *
     * <p>The buffer is in read mode (position at data start, limit at
     * data end). After this method returns it is no longer valid.
     *
     * @param data the datagram payload
     */
    default void datagramReceived(ByteBuffer data) {
    }
}
