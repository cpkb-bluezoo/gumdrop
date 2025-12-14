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

import org.bluezoo.gumdrop.http.DefaultWebSocketEventHandler;
import org.bluezoo.gumdrop.http.WebSocketEventHandler;
import org.bluezoo.gumdrop.http.WebSocketSession;

import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.WebConnection;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebConnection implementation that bridges the servlet WebSocket API
 * with Gumdrop's WebSocket implementation.
 *
 * <p>This provides the decoded message payload model: incoming WebSocket
 * messages are delivered as complete messages to the servlet's InputStream,
 * and data written to the OutputStream is sent as WebSocket messages.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletWebConnection implements WebConnection {

    private static final Logger LOGGER = Logger.getLogger(ServletWebConnection.class.getName());

    private final HttpUpgradeHandler upgradeHandler;
    private final PipedInputStream pipeIn;
    private final PipedOutputStream pipeOut;
    private final WebSocketServletInputStream inputStream;
    private final WebSocketServletOutputStream outputStream;
    private final WebSocketEventHandler eventHandler;

    private volatile WebSocketSession session;
    private volatile boolean closed = false;

    /**
     * Creates a new WebConnection for the given upgrade handler.
     *
     * @param upgradeHandler the servlet's upgrade handler
     * @param bufferSize buffer size for the pipe
     * @throws IOException if the pipe cannot be created
     */
    ServletWebConnection(HttpUpgradeHandler upgradeHandler, int bufferSize) throws IOException {
        this.upgradeHandler = upgradeHandler;

        // Create pipe for incoming WebSocket messages
        this.pipeOut = new PipedOutputStream();
        this.pipeIn = new PipedInputStream(pipeOut, bufferSize);

        // Create servlet streams
        this.inputStream = new WebSocketServletInputStream(pipeIn);
        this.outputStream = new WebSocketServletOutputStream(this);

        // Create event handler that delivers messages to the pipe
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

        // Close the pipe
        try {
            pipeOut.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Error closing pipe output", e);
        }

        try {
            pipeIn.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Error closing pipe input", e);
        }

        // Close the WebSocket session
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
            // Initialize the upgrade handler on a worker thread
            upgradeHandler.init(ServletWebConnection.this);
        }

        @Override
        public void textMessageReceived(String message) {
            try {
                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                pipeOut.write(data);
                pipeOut.flush();
            } catch (IOException e) {
                if (!closed) {
                    LOGGER.log(Level.WARNING, "Error writing message to pipe", e);
                }
            }
        }

        @Override
        public void binaryMessageReceived(ByteBuffer data) {
            try {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                pipeOut.write(bytes);
                pipeOut.flush();
            } catch (IOException e) {
                if (!closed) {
                    LOGGER.log(Level.WARNING, "Error writing binary message to pipe", e);
                }
            }
        }

        @Override
        public void closed(int code, String reason) {
            try {
                // Close pipe to signal EOF
                pipeOut.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing pipe on WebSocket close", e);
            }

            // Destroy the upgrade handler
            try {
                upgradeHandler.destroy();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error destroying upgrade handler", e);
            }
        }

        @Override
        public void error(Throwable cause) {
            LOGGER.log(Level.WARNING, "WebSocket error", cause);
            try {
                close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing on WebSocket error", e);
            }
        }
    }

}

