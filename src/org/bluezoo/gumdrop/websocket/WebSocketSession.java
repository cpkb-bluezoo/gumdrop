/*
 * WebSocketSession.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;

import org.bluezoo.gumdrop.SecurityInfo;

/**
 * A WebSocket session for sending messages to the peer (RFC 6455).
 *
 * <p>This interface is provided to {@link WebSocketEventHandler#opened}
 * when a WebSocket connection is established. Use it to send messages
 * and control the connection lifecycle.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 * @see WebSocketEventHandler
 */
public interface WebSocketSession {

    /**
     * RFC 6455 §5.6 — sends a text message (opcode 0x1) to the peer.
     *
     * @param message the text message to send
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if the session is not open
     */
    void sendText(String message) throws IOException;

    /**
     * RFC 6455 §5.6 — sends a binary message (opcode 0x2) to the peer.
     *
     * @param data the binary data to send
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if the session is not open
     */
    void sendBinary(ByteBuffer data) throws IOException;

    /**
     * RFC 6455 §5.5.2 — sends a ping frame to the peer.
     *
     * @param payload optional payload data (may be null, max 125 bytes per §5.5)
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if payload exceeds 125 bytes
     * @throws IllegalStateException if the session is not open
     */
    void sendPing(ByteBuffer payload) throws IOException;

    /**
     * RFC 6455 §7.1 — initiates closing handshake with normal closure (1000).
     *
     * @throws IOException if an I/O error occurs
     */
    void close() throws IOException;

    /**
     * RFC 6455 §7.1 — initiates closing handshake with specified code and reason.
     *
     * @param code the close code (RFC 6455 §7.4, 1000-4999)
     * @param reason optional close reason (may be null)
     * @throws IOException if an I/O error occurs
     */
    void close(int code, String reason) throws IOException;

    /**
     * Returns whether this session is open and ready for communication.
     *
     * @return true if the session is open
     */
    boolean isOpen();

    /**
     * Sets the largest assembled message, in bytes, this session accepts
     * before the connection is closed with code 1009 (RFC 6455 section
     * 7.4.1); 0 means unlimited. Sessions backed by a connection apply it
     * to the next frame read; the default does nothing.
     *
     * @param maxBytes the limit in bytes
     */
    default void setMaxMessageSize(long maxBytes) {
    }

    /**
     * Returns the authenticated principal for this session, or
     * {@code null} if the connection was not authenticated.
     *
     * <p>When the WebSocket upgrade request included a valid
     * {@code Authorization} header that was verified by the
     * configured {@link org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider},
     * this method returns the resulting principal. Otherwise it
     * returns {@code null}.
     *
     * @return the authenticated principal, or null
     */
    Principal getPrincipal();

    /**
     * Returns whether the connection this session runs over is encrypted.
     * For a server session that is the TLS (or QUIC) of the HTTP connection
     * the WebSocket was upgraded on.
     *
     * @return true if the connection is encrypted; false by default
     */
    default boolean isSecure() {
        return getSecurityInfo() != null;
    }

    /**
     * Returns the TLS session details of the connection this session runs
     * over: negotiated protocol and cipher suite, ALPN protocol, and the
     * peer's certificate chain when mutual TLS was used (which a handler can
     * use to identify the client without a password).
     *
     * <p>Server sessions (WebSocket over HTTP/1.1, HTTP/2 or HTTP/3) report
     * the details of the HTTP connection. Sessions of the WebSocket client
     * do not expose them yet.
     *
     * @return the TLS session details, or {@code null} if the connection is
     *         not encrypted or the details are not available
     */
    default SecurityInfo getSecurityInfo() {
        return null;
    }

}
