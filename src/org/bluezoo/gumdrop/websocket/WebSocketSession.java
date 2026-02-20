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

/**
 * A WebSocket session for sending messages to the peer.
 *
 * <p>This interface is provided to {@link WebSocketEventHandler#opened}
 * when a WebSocket connection is established. Use it to send messages
 * and control the connection lifecycle.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see WebSocketEventHandler
 */
public interface WebSocketSession {

    /**
     * Sends a text message to the peer.
     *
     * @param message the text message to send
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if the session is not open
     */
    void sendText(String message) throws IOException;

    /**
     * Sends a binary message to the peer.
     *
     * @param data the binary data to send
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if the session is not open
     */
    void sendBinary(ByteBuffer data) throws IOException;

    /**
     * Sends a ping frame to the peer.
     *
     * <p>The peer should respond with a pong frame containing the same payload.
     * This can be used for keep-alive or latency measurement.
     *
     * @param payload optional payload data (may be null, max 125 bytes)
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if payload exceeds 125 bytes
     * @throws IllegalStateException if the session is not open
     */
    void sendPing(ByteBuffer payload) throws IOException;

    /**
     * Closes the WebSocket connection with a normal closure code (1000).
     *
     * @throws IOException if an I/O error occurs
     */
    void close() throws IOException;

    /**
     * Closes the WebSocket connection with the specified code and reason.
     *
     * @param code the close code (1000-4999)
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

}
