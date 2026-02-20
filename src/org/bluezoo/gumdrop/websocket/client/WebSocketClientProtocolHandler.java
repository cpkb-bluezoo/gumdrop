/*
 * WebSocketClientProtocolHandler.java
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

package org.bluezoo.gumdrop.websocket.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPClientProtocolHandler;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

/**
 * Protocol handler for WebSocket client connections.
 *
 * <p>Extends {@link HTTPClientProtocolHandler} to add WebSocket upgrade
 * handling. Before the upgrade, HTTP parsing proceeds normally. Once a
 * 101 Switching Protocols response is received and validated, this handler
 * switches to WebSocket mode and routes all subsequent data to the
 * {@link WebSocketConnection}.
 *
 * <p>This class is not intended to be used directly. Use
 * {@link WebSocketClient} instead.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see WebSocketClient
 * @see HTTPClientProtocolHandler
 */
class WebSocketClientProtocolHandler extends HTTPClientProtocolHandler {

    private static final Logger LOGGER =
            Logger.getLogger(WebSocketClientProtocolHandler.class.getName());

    private final WebSocketEventHandler eventHandler;

    private String websocketKey;
    private volatile boolean webSocketMode;
    private ClientWebSocketConnection webSocketConnection;

    /**
     * Creates a WebSocket client protocol handler.
     *
     * @param clientHandler the HTTP client handler for connection lifecycle
     * @param eventHandler the WebSocket event handler for application events
     * @param host the target host
     * @param port the target port
     * @param secure whether this is a secure (TLS) connection
     */
    WebSocketClientProtocolHandler(HTTPClientHandler clientHandler,
                                   WebSocketEventHandler eventHandler,
                                   String host, int port,
                                   boolean secure) {
        super(clientHandler, host, port, secure);
        this.eventHandler = eventHandler;
    }

    /**
     * Sets the Sec-WebSocket-Key that was sent in the upgrade request.
     * This is needed to validate the server's Sec-WebSocket-Accept response.
     *
     * @param key the key sent in the upgrade request
     */
    void setWebSocketKey(String key) {
        this.websocketKey = key;
    }

    /**
     * Returns the active WebSocket connection, or null if the upgrade
     * has not yet completed.
     *
     * @return the WebSocket connection
     */
    WebSocketConnection getWebSocketConnection() {
        return webSocketConnection;
    }

    @Override
    protected boolean handleProtocolSwitch(HTTPStatus status, Headers headers) {
        if (websocketKey == null) {
            return false;
        }

        if (!WebSocketHandshake.validateUpgradeResponse(websocketKey, headers)) {
            LOGGER.warning("WebSocket upgrade response validation failed");
            eventHandler.error(new IOException("Invalid WebSocket upgrade response"));
            return false;
        }

        LOGGER.fine("WebSocket upgrade accepted, switching to WebSocket mode");

        webSocketConnection = new ClientWebSocketConnection(eventHandler);
        webSocketConnection.setClientMode(true);
        webSocketConnection.setTransport(new ClientWebSocketTransport());

        webSocketMode = true;

        // Clean up HTTP state
        currentStream = null;
        parseState = ParseState.IDLE;

        // Drain any remaining data in the parse buffer into WebSocket
        if (parseBuffer.position() > 0) {
            parseBuffer.flip();
            if (parseBuffer.hasRemaining()) {
                try {
                    webSocketConnection.processIncomingData(parseBuffer);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error processing buffered data", e);
                    eventHandler.error(e);
                }
            }
            parseBuffer.clear();
        }

        webSocketConnection.notifyConnectionOpen();

        return true;
    }

    @Override
    public void receive(ByteBuffer data) {
        if (webSocketMode) {
            try {
                webSocketConnection.processIncomingData(data);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error processing WebSocket data", e);
                eventHandler.error(e);
            }
            return;
        }
        super.receive(data);
    }

    @Override
    public void disconnected() {
        if (webSocketMode && webSocketConnection != null) {
            webSocketConnection.notifyTransportClosed();
            return;
        }
        super.disconnected();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket transport bridge
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Transport implementation that sends WebSocket frames through the
     * underlying TCP endpoint.
     */
    private class ClientWebSocketTransport
            implements WebSocketConnection.WebSocketTransport {

        @Override
        public void sendFrame(ByteBuffer frameData) throws IOException {
            if (endpoint == null) {
                throw new IOException("Endpoint not available");
            }
            endpoint.send(frameData);
        }

        @Override
        public void close(boolean normalClose) throws IOException {
            if (endpoint != null) {
                endpoint.close();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner WebSocket connection and session
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Concrete WebSocket connection that delegates events to a
     * {@link WebSocketEventHandler}.
     */
    private static class ClientWebSocketConnection extends WebSocketConnection {

        private final WebSocketEventHandler handler;
        private final ClientWebSocketSession session;

        ClientWebSocketConnection(WebSocketEventHandler handler) {
            this.handler = handler;
            this.session = new ClientWebSocketSession(this);
        }

        @Override
        protected void opened() {
            handler.opened(session);
        }

        @Override
        protected void textMessageReceived(String message) {
            handler.textMessageReceived(session, message);
        }

        @Override
        protected void binaryMessageReceived(ByteBuffer data) {
            handler.binaryMessageReceived(session, data);
        }

        @Override
        protected void closed(int code, String reason) {
            handler.closed(code, reason);
        }

        @Override
        protected void error(Throwable cause) {
            handler.error(cause);
        }

        /**
         * Notifies the connection that the transport has been closed
         * without a close frame exchange (abnormal close).
         */
        void notifyTransportClosed() {
            if (isOpen()) {
                try {
                    close(CloseCodes.GOING_AWAY, "Transport closed");
                } catch (IOException e) {
                    // Transport is already closed, just update state
                }
            }
        }
    }

    /**
     * Session implementation that wraps a {@link ClientWebSocketConnection}
     * to expose the {@link WebSocketSession} interface.
     */
    private static class ClientWebSocketSession implements WebSocketSession {

        private final ClientWebSocketConnection connection;

        ClientWebSocketSession(ClientWebSocketConnection connection) {
            this.connection = connection;
        }

        @Override
        public void sendText(String message) throws IOException {
            connection.sendText(message);
        }

        @Override
        public void sendBinary(ByteBuffer data) throws IOException {
            connection.sendBinary(data);
        }

        @Override
        public void sendPing(ByteBuffer payload) throws IOException {
            connection.sendPing(payload);
        }

        @Override
        public void close() throws IOException {
            connection.close();
        }

        @Override
        public void close(int code, String reason) throws IOException {
            connection.close(code, reason);
        }

        @Override
        public boolean isOpen() {
            return connection.isOpen();
        }
    }
}
