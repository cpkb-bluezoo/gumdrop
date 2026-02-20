/*
 * Endpoint.java
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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * Transport-agnostic connection interface.
 *
 * <p>This is the primary abstraction that protocol handlers interact with.
 * An Endpoint represents a bidirectional data channel -- a TCP connection,
 * a QUIC stream, or a UDP datagram association -- and provides a uniform
 * API regardless of the underlying transport.
 *
 * <p>All data passed through an Endpoint is plaintext. Security (TLS, DTLS,
 * QUIC TLS 1.3) is handled transparently by the transport implementation.
 * Protocol handlers never see ciphertext.
 *
 * <p>Implementations include:
 * <ul>
 * <li>TCPEndpoint -- TCP connections with optional JSSE TLS</li>
 * <li>UDPEndpoint -- UDP datagrams with optional JSSE DTLS</li>
 * <li>QuicStreamEndpoint -- a single QUIC stream (always secure)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see SecurityInfo
 */
public interface Endpoint {

    // -- Data I/O (plaintext always, security is transparent) --

    /**
     * Sends application data to the remote peer.
     * The data is always plaintext; encryption is handled transparently
     * by the transport implementation.
     *
     * @param data the application data to send
     */
    void send(ByteBuffer data);

    // -- Lifecycle --

    /**
     * Returns whether this endpoint is open and capable of I/O.
     *
     * @return true if the endpoint is open
     */
    boolean isOpen();

    /**
     * Returns whether this endpoint is in the process of closing.
     *
     * @return true if close has been initiated but not yet completed
     */
    boolean isClosing();

    /**
     * Closes this endpoint gracefully.
     * For TLS connections, sends close_notify before closing.
     * For QUIC streams, sends a STREAM frame with the FIN bit.
     */
    void close();

    // -- Identity --

    /**
     * Returns the local address of this endpoint.
     *
     * @return the local socket address
     */
    SocketAddress getLocalAddress();

    /**
     * Returns the remote address of this endpoint.
     *
     * @return the remote socket address
     */
    SocketAddress getRemoteAddress();

    // -- Security (transparent to protocol handlers) --

    /**
     * Returns whether this endpoint is secured by a cryptographic protocol.
     *
     * <p>For TCP, this is true when TLS is active (either from the start
     * or after a STARTTLS upgrade). For QUIC, this always returns true
     * because QUIC mandates TLS 1.3.
     *
     * @return true if the endpoint is secure
     */
    boolean isSecure();

    /**
     * Returns security metadata for this endpoint.
     *
     * <p>When {@link #isSecure()} returns true, this provides details about
     * the negotiated cipher suite, protocol version, certificates, and ALPN.
     * When not secure, returns a NullSecurityInfo singleton.
     *
     * @return the security info, never null
     */
    SecurityInfo getSecurityInfo();

    /**
     * Initiates a TLS upgrade on a plaintext connection (STARTTLS).
     *
     * <p>This is only meaningful for TCP endpoints that start in plaintext
     * mode. After the TLS handshake completes, the ProtocolHandler's
     * {@link ProtocolHandler#securityEstablished(SecurityInfo)} method
     * is called.
     *
     * <p>For QUIC endpoints (always secure), this throws
     * {@link UnsupportedOperationException}.
     *
     * @throws IOException if the TLS handshake cannot be initiated
     * @throws UnsupportedOperationException if the transport does not
     *         support TLS upgrades (e.g., QUIC)
     */
    void startTLS() throws IOException;

    // -- Infrastructure --

    /**
     * Returns the SelectorLoop that this endpoint is registered with.
     * All I/O for this endpoint occurs on this loop's thread.
     *
     * @return the selector loop
     */
    SelectorLoop getSelectorLoop();

    /**
     * Schedules a callback to be executed after the specified delay.
     * The callback runs on this endpoint's SelectorLoop thread,
     * making it safe to perform I/O operations.
     *
     * @param delayMs delay in milliseconds before the callback executes
     * @param callback the callback to execute
     * @return a handle that can be used to cancel the timer
     */
    TimerHandle scheduleTimer(long delayMs, Runnable callback);

    // -- Telemetry --

    /**
     * Returns the trace context for this endpoint.
     *
     * @return the trace, or null if telemetry is disabled
     */
    Trace getTrace();

    /**
     * Sets the trace context for this endpoint.
     *
     * @param trace the trace context
     */
    void setTrace(Trace trace);

    /**
     * Returns whether telemetry is enabled for this endpoint.
     *
     * @return true if telemetry is enabled
     */
    boolean isTelemetryEnabled();

    /**
     * Returns the telemetry configuration for this endpoint.
     *
     * @return the telemetry config, or null if not configured
     */
    TelemetryConfig getTelemetryConfig();
}
