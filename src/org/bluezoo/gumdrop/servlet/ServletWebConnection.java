/*
 * ServletWebConnection.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.websocket.DefaultWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebConnection implementation that bridges the servlet WebSocket API
 * with Gumdrop's WebSocket implementation.
 *
 * <p>Incoming WebSocket messages are delivered through a non-blocking
 * {@link RequestBodyStream} (replacing a pipe that could block the
 * SelectorLoop thread when the servlet read side was slow). Backpressure
 * is applied via {@link HTTPResponseState#pauseRequestBody()} when the
 * buffer reaches its high-water mark.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletWebConnection implements WebConnection {

    private static final Logger LOGGER = Logger.getLogger(ServletWebConnection.class.getName());

    private final HttpUpgradeHandler upgradeHandler;
    private final HTTPResponseState state;
    private final ServletHandler handler;
    private final RequestBodyStream messageStream;
    private final WebSocketServletInputStream inputStream;
    private final WebSocketServletOutputStream outputStream;
    private final WebSocketEventHandler eventHandler;

    private volatile WebSocketSession session;
    private volatile boolean closed = false;

    /**
     * Creates a new WebConnection for the given upgrade handler.
     *
     * @param upgradeHandler the servlet's upgrade handler
     * @param state the HTTP response state for backpressure and callbacks
     * @param handler the servlet handler for container callback dispatch
     */
    ServletWebConnection(HttpUpgradeHandler upgradeHandler,
            HTTPResponseState state, ServletHandler handler) {
        this.upgradeHandler = upgradeHandler;
        this.state = state;
        this.handler = handler;

        this.messageStream = new RequestBodyStream();
        messageStream.setResumeCallback(new Runnable() {
            @Override
            public void run() {
                // May be called from the worker thread (inside
                // RequestBodyStream.read()); resumeRequestBody() must
                // run on the SelectorLoop thread.
                if (ServletWebConnection.this.state != null) {
                    ServletWebConnection.this.state.execute(new Runnable() {
                        @Override
                        public void run() {
                            ServletWebConnection.this.state.resumeRequestBody();
                        }
                    });
                }
            }
        });

        this.inputStream = new WebSocketServletInputStream(handler, messageStream);
        this.outputStream = new WebSocketServletOutputStream(this);
        this.eventHandler = new WebConnectionEventHandler();
    }

    /**
     * Returns the WebSocket event handler to pass to upgradeToWebSocket.
     */
    WebSocketEventHandler getEventHandler() {
        return eventHandler;
    }

    /**
     * Called when the WebSocket session is established.
     */
    void sessionOpened(WebSocketSession session) {
        this.session = session;
    }

    /**
     * Writes a message to the WebSocket session.
     */
    void sendMessage(byte[] data) throws IOException {
        if (session == null) {
            throw new IOException("WebSocket session not established");
        }
        if (!session.isOpen()) {
            throw new IOException("WebSocket session is closed");
        }
        // Send as text if it looks like UTF-8 text, otherwise binary
        // For simplicity, we send as text (most WebSocket usage is text-based)
        session.sendText(new String(data, StandardCharsets.UTF_8));
    }

    /**
     * Writes binary data to the WebSocket session.
     */
    void sendBinaryMessage(ByteBuffer data) throws IOException {
        if (session == null) {
            throw new IOException("WebSocket session not established");
        }
        if (!session.isOpen()) {
            throw new IOException("WebSocket session is closed");
        }
        session.sendBinary(data);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return inputStream;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return outputStream;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        messageStream.finish();
        inputStream.dispatchDataAvailable();
        messageStream.close();

        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing WebSocket session", e);
            }
        }
    }

    /**
     * Returns true if the connection is closed.
     */
    boolean isClosed() {
        return closed;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket Event Handler
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Event handler that bridges WebSocket events to the servlet WebConnection.
     */
    private class WebConnectionEventHandler extends DefaultWebSocketEventHandler {

        @Override
        public void opened(WebSocketSession session) {
            sessionOpened(session);
            upgradeHandler.init(ServletWebConnection.this);
        }

        @Override
        public void textMessageReceived(WebSocketSession session,
                                        String message) {
            deliverMessage(message.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void binaryMessageReceived(WebSocketSession session,
                                          ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            deliverMessage(bytes);
        }

        @Override
        public void closed(int code, String reason) {
            if (!closed) {
                messageStream.finish();
                inputStream.dispatchDataAvailable();
            }

            try {
                upgradeHandler.destroy();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error destroying upgrade handler", e);
            }
        }

        @Override
        public void error(Throwable cause) {
            LOGGER.log(Level.WARNING, "WebSocket error", cause);
            if (!closed) {
                messageStream.fail(new IOException("WebSocket error", cause));
                inputStream.dispatchDataAvailable();
            }
            try {
                close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing on WebSocket error", e);
            }
        }
    }

    private void deliverMessage(byte[] data) {
        if (closed || data.length == 0) {
            return;
        }
        if (messageStream.offer(data) && state != null) {
            state.pauseRequestBody();
        }
        inputStream.dispatchDataAvailable();
    }

}
