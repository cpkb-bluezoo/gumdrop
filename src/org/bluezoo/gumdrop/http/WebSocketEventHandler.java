/*
 * WebSocketEventHandler.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;

/**
 * Handler for WebSocket lifecycle events.
 *
 * <p>Implement this interface to receive WebSocket events after upgrading
 * an HTTP connection via {@link HTTPResponseState#upgradeToWebSocket}.
 *
 * <p>Example usage:
 * <pre>
 * public class EchoHandler extends DefaultHTTPRequestHandler {
 *     
 *     &#64;Override
 *     public void headers(Headers headers, HTTPResponseState state) {
 *         if (WebSocketHandshake.isValidWebSocketUpgrade(headers)) {
 *             state.upgradeToWebSocket(null, new DefaultWebSocketEventHandler() {
 *                 private WebSocketSession session;
 *                 
 *                 &#64;Override
 *                 public void opened(WebSocketSession session) {
 *                     this.session = session;
 *                 }
 *                 
 *                 &#64;Override
 *                 public void textMessageReceived(String message) {
 *                     session.sendText("Echo: " + message);
 *                 }
 *             });
 *         }
 *     }
 * }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see WebSocketSession
 * @see DefaultWebSocketEventHandler
 * @see HTTPResponseState#upgradeToWebSocket
 */
public interface WebSocketEventHandler {

    /**
     * Called when the WebSocket connection is established.
     *
     * <p>This is called after the 101 Switching Protocols response has been
     * sent and the connection is ready for bidirectional communication.
     *
     * @param session the session for sending messages
     */
    void opened(WebSocketSession session);

    /**
     * Called when a text message is received from the peer.
     *
     * @param message the received text message
     */
    void textMessageReceived(String message);

    /**
     * Called when a binary message is received from the peer.
     *
     * @param data the received binary data
     */
    void binaryMessageReceived(ByteBuffer data);

    /**
     * Called when the WebSocket connection is closed.
     *
     * <p>This is called after the closing handshake is complete.
     * The session is no longer usable after this method returns.
     *
     * @param code the close code (see RFC 6455 Section 7.4)
     * @param reason the close reason (may be null or empty)
     */
    void closed(int code, String reason);

    /**
     * Called when an error occurs on the WebSocket connection.
     *
     * @param cause the error that occurred
     */
    void error(Throwable cause);

}

